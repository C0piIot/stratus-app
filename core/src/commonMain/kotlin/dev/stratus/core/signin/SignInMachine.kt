package dev.stratus.core.signin

import dev.stratus.core.net.Candidate
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.ParsedAddress
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.net.Scheme
import dev.stratus.core.net.ServerAddress
import dev.stratus.core.net.candidates
import dev.stratus.core.net.parseServerAddress
import dev.stratus.core.net.usernameIsUsable

/**
 * Sign-in as a pure function of state and event.
 *
 * Nothing here performs I/O; it asks for it. That is what makes a flow which has
 * to stop and ask the user a question testable at all -- a suspending function
 * that paused in the middle could only be examined through the coroutine
 * machinery, while every pause here is a value somebody can assert on.
 */
object SignInMachine {

    fun next(state: SignInState, event: SignInEvent): Step = when (event) {
        is SignInEvent.Submitted -> submit(event)
        is SignInEvent.Cancelled -> Step(SignInState.Idle)
        is SignInEvent.Answered -> answered(state, event)
        is SignInEvent.Attempted -> attempted(state, event.outcome)
    }

    private fun submit(event: SignInEvent.Submitted): Step {
        val form = event.form
        val parsed = parseServerAddress(form.address)
        if (parsed is ParsedAddress.Invalid) {
            return Step(SignInState.Failed(SignInFailure.Address(parsed.problem)))
        }
        val address = (parsed as ParsedAddress.Valid).address
        if (!usernameIsUsable(form.username.trim())) {
            return Step(SignInState.Failed(SignInFailure.UsernameUnusable))
        }

        // The password is never trimmed. Leading and trailing spaces are legal in
        // one and silently eating them is a login failure nobody can account for.
        val credentials = Credentials(form.username.trim(), form.password)

        // Https unless they insisted otherwise: an address with no scheme is a
        // question, and the safe answer is tried first.
        val scheme = address.scheme ?: Scheme.Https
        val plan = SignInPlan(
            address = address,
            credentials = credentials,
            scheme = scheme,
            queue = address.candidates(scheme),
            consentedHosts = event.consentedHosts,
        )
        return begin(plan)
    }

    /** Takes the next candidate off the queue and asks for it to be probed. */
    private fun begin(plan: SignInPlan): Step {
        val attempt = plan.queue.firstOrNull()
            ?: return Step(SignInState.Failed(exhausted(plan)))
        val rest = plan.copy(queue = plan.queue.drop(1), triedPaths = plan.triedPaths + attempt.path)

        // Over TLS the password is safe to send straight away. Over http it is
        // not, so the first question is the cheap one -- is anything even there --
        // asked without credentials, so that nobody is warned about the risk of
        // signing in to a host that turns out not to exist.
        val needsConsent = attempt.scheme == Scheme.Http && attempt.host !in plan.consentedHosts
        return Step(
            SignInState.Probing(rest, attempt, authenticated = !needsConsent),
            listOf(SignInEffect.Probe(attempt, if (needsConsent) null else plan.credentials)),
        )
    }

    private fun answered(state: SignInState, event: SignInEvent.Answered): Step {
        // A dialog tapped twice, or a recomposition replaying a stale answer,
        // arrives here naming a question nobody is asking any more.
        if (state !is SignInState.Asking || state.question != event.question) return Step(state)

        val question = state.question as Question.AcceptPlaintext
        if (!event.accepted) {
            return Step(SignInState.Failed(SignInFailure.PlaintextRefused(question.host)))
        }
        val plan = state.plan.copy(consentedHosts = state.plan.consentedHosts + question.host)
        return Step(
            SignInState.Probing(plan, state.attempt, authenticated = true),
            listOf(
                SignInEffect.RememberConsent(question.host),
                SignInEffect.Probe(state.attempt, plan.credentials),
            ),
        )
    }

    private fun attempted(state: SignInState, outcome: ProbeOutcome): Step {
        // A result for something nobody is waiting on: a response that arrived
        // after a cancel, or after the attempt it belongs to was abandoned.
        if (state !is SignInState.Probing || state.attempt != outcomeAttempt(outcome)) return Step(state)
        val plan = state.plan
        val attempt = state.attempt

        return when (outcome) {
            is ProbeOutcome.IsWebDav -> {
                Step(
                    SignInState.Done(attempt.baseUrl),
                    listOf(SignInEffect.Store(attempt.baseUrl, plan.credentials)),
                )
            }

            // Wrong credentials are wrong at every path, so the rest of the queue
            // is not worth trying -- and trying it would be several failed logins
            // against a server that counts them.
            is ProbeOutcome.Rejected ->
                Step(SignInState.Failed(SignInFailure.WrongCredentials(attempt.host)))

            // Something is there and it answered. Now the expensive question.
            is ProbeOutcome.Reachable -> Step(
                SignInState.Asking(
                    Question.AcceptPlaintext(
                        attempt.host,
                        if (plan.address.scheme == Scheme.Http) PlaintextReason.Typed
                        else PlaintextReason.HttpsUnreachable,
                    ),
                    plan,
                    attempt,
                ),
            )

            // The server is alive and this scheme works; only the path was wrong.
            // Falling back to http from here would downgrade a working connection
            // for no reason at all.
            is ProbeOutcome.NotWebDav -> begin(plan)

            // Nothing is listening, so the remaining paths on this origin cannot
            // help either -- they are the same port. Drop them and try the other
            // scheme if one was never specified.
            is ProbeOutcome.Unreachable -> {
                val fallback = plan.copy(
                    scheme = Scheme.Http,
                    queue = plan.address.candidates(Scheme.Http),
                    triedPaths = emptyList(),
                )
                if (plan.address.scheme == null && plan.scheme == Scheme.Https) {
                    begin(fallback)
                } else {
                    Step(SignInState.Failed(SignInFailure.Unreachable(attempt.host, outcome.detail)))
                }
            }

            is ProbeOutcome.Redirected -> redirect(state, outcome)
        }
    }

    /**
     * Follows a redirect only back to the same place it came from.
     *
     * A redirect is a fine hint about the path and a terrible instruction about
     * the host: following one across origins would hand the credentials to
     * whoever the `Location` names, and following one from https to http would
     * undo the whole point of trying https first.
     */
    private fun redirect(state: SignInState.Probing, outcome: ProbeOutcome.Redirected): Step {
        val attempt = state.attempt
        val plan = state.plan
        val target = resolve(attempt, outcome.location)
        if (target == null || plan.redirects >= MAX_REDIRECTS) {
            return begin(plan)
        }
        return Step(
            SignInState.Probing(plan.copy(redirects = plan.redirects + 1), target, state.authenticated),
            listOf(
                SignInEffect.Probe(target, if (state.authenticated) plan.credentials else null),
            ),
        )
    }

    /** The redirect target, or null when it points somewhere we will not follow. */
    private fun resolve(from: Candidate, location: String): Candidate? {
        val path = when {
            location.startsWith("/") -> location
            location.startsWith("${from.origin}/") -> location.removePrefix(from.origin)
            else -> return null
        }
        val normalised = "/" + path.trim('/') + "/"
        return if (normalised == from.path) null else from.copy(path = normalised)
    }

    private fun exhausted(plan: SignInPlan): SignInFailure {
        val origin = plan.address.candidates(plan.scheme).first().origin
        return SignInFailure.NotWebDav(origin, plan.triedPaths)
    }

    private fun outcomeAttempt(outcome: ProbeOutcome): Candidate = when (outcome) {
        is ProbeOutcome.IsWebDav -> outcome.attempt
        is ProbeOutcome.Rejected -> outcome.attempt
        is ProbeOutcome.NotWebDav -> outcome.attempt
        is ProbeOutcome.Reachable -> outcome.attempt
        is ProbeOutcome.Redirected -> outcome.attempt
        is ProbeOutcome.Unreachable -> outcome.attempt
    }

    private const val MAX_REDIRECTS = 3
}
