package dev.stratus.core.signin

import dev.stratus.core.net.AddressProblem
import dev.stratus.core.net.Candidate
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.net.Scheme
import dev.stratus.core.net.ServerAddress

data class SignInForm(val address: String, val username: String, val password: String)

/** Something only the person at the keyboard can decide. */
sealed interface Question {
    /**
     * Sending this password would put it on the wire in clear text.
     *
     * [reason] separates "you asked for http" from "https did not answer", which
     * are the same risk but not the same surprise.
     */
    data class AcceptPlaintext(val host: String, val reason: PlaintextReason) : Question
}

enum class PlaintextReason { Typed, HttpsUnreachable }

/**
 * Why sign-in did not happen, in terms that suggest what to do about it.
 *
 * [NotWebDav] carries the paths that were tried because "this is not a WebDAV
 * server" and "it is one, but not at any path I guessed" lead somewhere
 * different: check the address, or type the full path.
 */
sealed interface SignInFailure {
    data class Address(val problem: AddressProblem) : SignInFailure
    data object UsernameUnusable : SignInFailure
    data class Unreachable(val host: String, val detail: String) : SignInFailure
    data class WrongCredentials(val host: String) : SignInFailure
    data class NotWebDav(val origin: String, val triedPaths: List<String>) : SignInFailure
    data class PlaintextRefused(val host: String) : SignInFailure
}

/** Everything an attempt carries between steps. */
data class SignInPlan(
    val address: ServerAddress,
    val credentials: Credentials,
    val scheme: Scheme,
    val queue: List<Candidate>,
    val triedPaths: List<String> = emptyList(),
    val consentedHosts: Set<String> = emptySet(),
    val redirects: Int = 0,
)

sealed interface SignInState {
    data object Idle : SignInState

    data class Probing(
        val plan: SignInPlan,
        val attempt: Candidate,
        val authenticated: Boolean,
    ) : SignInState

    /** Stopped, and going nowhere until an [SignInEvent.Answered] names this question. */
    data class Asking(
        val question: Question,
        val plan: SignInPlan,
        val attempt: Candidate,
    ) : SignInState

    data class Failed(val reason: SignInFailure) : SignInState
    /** Signed in. The record of it is the controller's to make and to keep. */
    data class Done(val baseUrl: String) : SignInState
}

sealed interface SignInEvent {
    data class Submitted(val form: SignInForm, val consentedHosts: Set<String>) : SignInEvent
    data class Answered(val question: Question, val accepted: Boolean) : SignInEvent
    data class Attempted(val outcome: ProbeOutcome) : SignInEvent
    data object Cancelled : SignInEvent
}

/** What the world outside is asked to do. The machine itself does nothing. */
sealed interface SignInEffect {
    data class Probe(val attempt: Candidate, val credentials: Credentials?) : SignInEffect
    data class RememberConsent(val host: String) : SignInEffect
    data class Store(val baseUrl: String, val credentials: Credentials) : SignInEffect
}

data class Step(val state: SignInState, val effects: List<SignInEffect> = emptyList())
