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

    /**
     * Nothing on this device vouches for the server's certificate.
     *
     * [fingerprint] is the whole of what is being asked: it can be compared with
     * what the server prints, and that comparison is the only thing standing
     * between a self-hosted server and somebody in the middle.
     */
    data class AcceptCertificate(val hostPort: String, val fingerprint: String) : Question
}

enum class PlaintextReason { Typed, HttpsUnreachable }

/**
 * Why sign-in did not happen, in terms that suggest what to do about it.
 *
 * [NotWebDav] carries what each path answered, not merely which were tried:
 * nothing at all there and something there that is not WebDAV lead somewhere
 * different, and the second is what a server whose files live under a deeper
 * path looks like from here.
 */
/** A path that was asked, and what came back -- null when nothing answered. */
data class Tried(val path: String, val status: Int? = null)

sealed interface SignInFailure {
    data class Address(val problem: AddressProblem) : SignInFailure
    data object UsernameUnusable : SignInFailure
    data class Unreachable(val host: String, val detail: String) : SignInFailure
    data class WrongCredentials(val host: String) : SignInFailure
    data class NotWebDav(val origin: String, val tried: List<Tried>) : SignInFailure
    data class PlaintextRefused(val host: String) : SignInFailure
    data class CertificateRefused(val hostPort: String) : SignInFailure
}

/** Everything an attempt carries between steps. */
data class SignInPlan(
    val address: ServerAddress,
    val credentials: Credentials,
    val scheme: Scheme,
    val queue: List<Candidate>,
    val tried: List<Tried> = emptyList(),
    val consentedHosts: Set<String> = emptySet(),
    /** The certificates already vouched for, by `host:port`. */
    val pins: Map<String, String> = emptyMap(),
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
        /** Whether the probe that stopped to ask was carrying credentials. */
        val authenticated: Boolean,
    ) : SignInState

    data class Failed(val reason: SignInFailure) : SignInState
    /** Signed in. The record of it is the controller's to make and to keep. */
    data class Done(val baseUrl: String) : SignInState
}

sealed interface SignInEvent {
    data class Submitted(
        val form: SignInForm,
        val consentedHosts: Set<String>,
        val pins: Map<String, String> = emptyMap(),
    ) : SignInEvent
    data class Answered(val question: Question, val accepted: Boolean) : SignInEvent
    data class Attempted(val outcome: ProbeOutcome) : SignInEvent
    data object Cancelled : SignInEvent
}

/** What the world outside is asked to do. The machine itself does nothing. */
sealed interface SignInEffect {
    data class Probe(
        val attempt: Candidate,
        val credentials: Credentials?,
        /** The certificate already vouched for here, if there is one. */
        val pin: String? = null,
    ) : SignInEffect
    data class RememberConsent(val host: String) : SignInEffect
    data class Pin(val hostPort: String, val fingerprint: String) : SignInEffect
    data class Store(val baseUrl: String, val credentials: Credentials) : SignInEffect
}

data class Step(val state: SignInState, val effects: List<SignInEffect> = emptyList())
