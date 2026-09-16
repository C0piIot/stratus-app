package dev.stratus.core.net

/** What one attempt at one candidate turned out to be. */
sealed interface ProbeOutcome {
    /** A WebDAV collection answered the request that carried credentials. */
    data class IsWebDav(val attempt: Candidate) : ProbeOutcome

    /** The credentials were refused. Nothing about the path is implied. */
    data class Rejected(val attempt: Candidate) : ProbeOutcome

    /** Something answered, but it is not WebDAV here. */
    data class NotWebDav(val attempt: Candidate, val status: Int?) : ProbeOutcome

    /** Something is listening. Only ever the answer to a probe carrying no credentials. */
    data class Reachable(val attempt: Candidate) : ProbeOutcome

    data class Redirected(val attempt: Candidate, val location: String) : ProbeOutcome

    data class Unreachable(val attempt: Candidate, val detail: String) : ProbeOutcome
}

fun interface Prober {
    /**
     * Asks one candidate what it is.
     *
     * A null [credentials] is a deliberate, separate question -- "is anything
     * there?" -- asked before a password is put on the wire in clear text. Its
     * answer can never be [ProbeOutcome.IsWebDav]: a server that allows anonymous
     * reads would otherwise dismiss the sign-in screen having proved nothing
     * about the credentials at all.
     */
    suspend fun probe(attempt: Candidate, credentials: Credentials?): ProbeOutcome
}
