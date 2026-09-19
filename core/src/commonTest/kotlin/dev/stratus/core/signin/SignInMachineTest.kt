package dev.stratus.core.signin

import dev.stratus.core.net.AddressProblem
import dev.stratus.core.net.Candidate
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.net.Scheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInMachineTest {

    private val form = SignInForm("host", "edu", "secret")

    private fun submit(
        address: String = "host",
        consented: Set<String> = emptySet(),
        pins: Map<String, String> = emptyMap(),
    ): Step = SignInMachine.next(
        SignInState.Idle,
        SignInEvent.Submitted(form.copy(address = address), consented, pins),
    )

    private fun Step.answer(accepted: Boolean): Step =
        SignInMachine.next(state, SignInEvent.Answered((state as SignInState.Asking).question, accepted))

    private fun Step.then(outcome: ProbeOutcome): Step =
        SignInMachine.next(state, SignInEvent.Attempted(outcome))

    private val Step.probes: List<SignInEffect.Probe>
        get() = effects.filterIsInstance<SignInEffect.Probe>()

    private val Step.attempt: Candidate
        get() = (state as SignInState.Probing).attempt

    @Test
    fun triesHttpsFirstWhenNoSchemeWasTyped() {
        val step = submit()
        assertEquals(Scheme.Https, step.attempt.scheme)
        assertEquals("/dav/", step.attempt.path)
        // Over TLS there is nothing to ask about, so the credentials go at once.
        assertEquals(form.password, step.probes.single().credentials?.password)
    }

    @Test
    fun signsInAndAsksForTheSessionToBeStored() {
        val first = submit()
        val done = first.then(ProbeOutcome.IsWebDav(first.attempt))
        assertEquals("https://host/dav/", (done.state as SignInState.Done).baseUrl)
        assertTrue(done.effects.any { it is SignInEffect.Store })
    }

    @Test
    fun walksOnToTheRootWhenDavIsNotThere() {
        val first = submit()
        val second = first.then(ProbeOutcome.NotWebDav(first.attempt, 404))
        assertEquals("/", second.attempt.path)
    }

    @Test
    fun remembersWhatEachPathAnsweredAndNotJustThatItFailed() {
        // A server publishing WebDAV under a longer path answers 405 at the
        // shallow one, and that is a different sentence from a 404 everywhere.
        // Measured against Nextcloud, which answers 405 at both `/dav/` and `/`.
        val first = submit()
        val second = first.then(ProbeOutcome.NotWebDav(first.attempt, 405))
        val exhausted = second.then(ProbeOutcome.NotWebDav(second.attempt, 405))
        val reason = (exhausted.state as SignInState.Failed).reason
        assertEquals(listOf(Tried("/dav/", 405), Tried("/", 405)), (reason as SignInFailure.NotWebDav).tried)
    }

    @Test
    fun stopsDeadOnRejectedCredentials() {
        // Wrong at one path is wrong at all of them, and walking the queue would
        // be several failed logins against a server that counts them.
        val first = submit()
        val stopped = first.then(ProbeOutcome.Rejected(first.attempt))
        assertTrue(stopped.state is SignInState.Failed)
        assertEquals(SignInFailure.WrongCredentials("host"), (stopped.state as SignInState.Failed).reason)
        assertEquals(emptyList(), stopped.probes)
    }

    @Test
    fun neverFallsBackToHttpFromAServerThatAnsweredOverHttps() {
        // https works, the path is what is wrong. Downgrading here would give up
        // a working encrypted connection to guess at paths in clear text.
        val first = submit()
        val second = first.then(ProbeOutcome.NotWebDav(first.attempt, 404))
        val exhausted = second.then(ProbeOutcome.NotWebDav(second.attempt, 404))
        val reason = (exhausted.state as SignInState.Failed).reason
        assertTrue(reason is SignInFailure.NotWebDav, "was $reason")
        // Both paths, and what each answered: the message depends on the second.
        assertEquals(listOf(Tried("/dav/", 404), Tried("/", 404)), reason.tried)
        assertEquals(emptyList(), exhausted.probes)
    }

    @Test
    fun fallsBackToHttpOnlyWhenNothingAnsweredAndNoSchemeWasTyped() {
        val first = submit()
        val fallback = first.then(ProbeOutcome.Unreachable(first.attempt, "connection refused"))
        assertEquals(Scheme.Http, fallback.attempt.scheme)
        // And the remaining https path is dropped: it is the same closed port.
        assertEquals("/dav/", fallback.attempt.path)
        // Nothing carrying a password goes out before consent.
        assertEquals(null, fallback.probes.single().credentials)
    }

    @Test
    fun keepsAnExplicitHttpsAtItsWord() {
        val first = submit("https://host")
        val failed = first.then(ProbeOutcome.Unreachable(first.attempt, "timeout"))
        assertTrue((failed.state as SignInState.Failed).reason is SignInFailure.Unreachable)
        assertEquals(emptyList(), failed.probes)
    }

    @Test
    fun asksBeforePuttingAPasswordOnTheWireInClear() {
        val first = submit()
        val fallback = first.then(ProbeOutcome.Unreachable(first.attempt, "refused"))
        val asking = fallback.then(ProbeOutcome.Reachable(fallback.attempt))
        val question = (asking.state as SignInState.Asking).question
        assertEquals(Question.AcceptPlaintext("host", PlaintextReason.HttpsUnreachable), question)
        assertEquals(emptyList(), asking.probes)
    }

    @Test
    fun warnsAboutATypedHttpAddressToo() {
        val first = submit("http://host")
        assertEquals(null, first.probes.single().credentials)
        val asking = first.then(ProbeOutcome.Reachable(first.attempt))
        assertEquals(
            Question.AcceptPlaintext("host", PlaintextReason.Typed),
            (asking.state as SignInState.Asking).question,
        )
    }

    @Test
    fun sendsTheCredentialsOnlyAfterConsentAndRemembersIt() {
        val first = submit("http://host")
        val asking = first.then(ProbeOutcome.Reachable(first.attempt))
        val accepted = SignInMachine.next(
            asking.state,
            SignInEvent.Answered((asking.state as SignInState.Asking).question, accepted = true),
        )
        assertEquals(form.password, accepted.probes.single().credentials?.password)
        assertTrue(accepted.effects.any { it is SignInEffect.RememberConsent })
    }

    @Test
    fun doesNotAskAgainForAHostAlreadyConsentedTo() {
        val first = submit("http://host", consented = setOf("host"))
        assertEquals(form.password, first.probes.single().credentials?.password)
    }

    @Test
    fun refusingPlaintextFailsRatherThanTryingAnyway() {
        val first = submit("http://host")
        val asking = first.then(ProbeOutcome.Reachable(first.attempt))
        val refused = SignInMachine.next(
            asking.state,
            SignInEvent.Answered((asking.state as SignInState.Asking).question, accepted = false),
        )
        assertEquals(SignInFailure.PlaintextRefused("host"), (refused.state as SignInState.Failed).reason)
        assertEquals(emptyList(), refused.probes)
    }

    @Test
    fun ignoresAnAnswerToAQuestionNobodyIsAsking() {
        // A dialog tapped twice, or a recomposition replaying a stale answer.
        val first = submit("http://host")
        val asking = first.then(ProbeOutcome.Reachable(first.attempt))
        val stale = SignInMachine.next(
            asking.state,
            SignInEvent.Answered(Question.AcceptPlaintext("elsewhere", PlaintextReason.Typed), accepted = true),
        )
        assertEquals(asking.state, stale.state)
        assertEquals(emptyList(), stale.effects)
    }

    @Test
    fun ignoresAResultForAnAttemptItIsNotWaitingOn() {
        val first = submit()
        val other = first.attempt.copy(path = "/somewhere-else/")
        val ignored = first.then(ProbeOutcome.IsWebDav(other))
        assertEquals(first.state, ignored.state)
    }

    @Test
    fun followsARedirectBackToItsOwnOriginAndNowhereElse() {
        val first = submit("https://host")
        val followed = first.then(ProbeOutcome.Redirected(first.attempt, "/webdav/"))
        assertEquals("/webdav/", followed.attempt.path)

        val elsewhere = first.then(ProbeOutcome.Redirected(first.attempt, "https://other.example/dav/"))
        // Not followed: it would hand the credentials to whoever Location names.
        assertEquals("/", elsewhere.attempt.path)
    }

    @Test
    fun refusesInputItCannotUseWithoutAskingTheNetwork() {
        val bad = SignInMachine.next(
            SignInState.Idle,
            SignInEvent.Submitted(form.copy(address = "https://u:p@host"), emptySet()),
        )
        assertEquals(
            SignInFailure.Address(AddressProblem.CredentialsInUrl),
            (bad.state as SignInState.Failed).reason,
        )

        val colon = SignInMachine.next(
            SignInState.Idle,
            SignInEvent.Submitted(form.copy(username = "a:b"), emptySet()),
        )
        assertEquals(SignInFailure.UsernameUnusable, (colon.state as SignInState.Failed).reason)
    }

    // ---- A certificate nobody vouches for ----------------------------------

    @Test
    fun anUntrustedCertificateIsAQuestionRatherThanAFailure() {
        val first = submit()
        val asked = first.then(ProbeOutcome.Untrusted(first.attempt, FINGERPRINT))

        val question = (asked.state as SignInState.Asking).question
        assertEquals(Question.AcceptCertificate("host:443", FINGERPRINT), question)
        // Nothing is tried in the background while somebody is deciding.
        assertEquals(emptyList(), asked.probes)
    }

    @Test
    fun acceptingItPinsItAndRetriesTheSameCandidate() {
        val first = submit()
        val accepted = first.then(ProbeOutcome.Untrusted(first.attempt, FINGERPRINT)).answer(true)

        assertEquals(
            SignInEffect.Pin("host:443", FINGERPRINT),
            accepted.effects.filterIsInstance<SignInEffect.Pin>().single(),
        )
        val retry = accepted.probes.single()
        assertEquals(first.attempt, retry.attempt, "went off to some other candidate")
        assertEquals(FINGERPRINT, retry.pin)
    }

    @Test
    fun refusingItNeverFallsBackToHttp() {
        // The worst bug this feature could have: "I do not trust this
        // certificate" turning into "then send the password in clear".
        val first = submit()
        val refused = first.then(ProbeOutcome.Untrusted(first.attempt, FINGERPRINT)).answer(false)

        assertEquals(SignInFailure.CertificateRefused("host:443"), (refused.state as SignInState.Failed).reason)
        assertEquals(emptyList(), refused.probes)
    }

    @Test
    fun aCertificateAlreadyVouchedForIsNotAskedAboutAgain() {
        val step = submit(pins = mapOf("host:443" to FINGERPRINT))
        assertEquals(FINGERPRINT, step.probes.single().pin)
    }

    @Test
    fun aPinIsNotHandedToADifferentServer() {
        // Keyed by host and port, so the other candidate on another port -- or
        // the same host over http -- gets nothing.
        val step = submit(address = "host:8443", pins = mapOf("host:443" to FINGERPRINT))
        assertNull(step.probes.single().pin)
    }

    private companion object {
        const val FINGERPRINT = "79:AC:73:12:E3:06"
    }
}
