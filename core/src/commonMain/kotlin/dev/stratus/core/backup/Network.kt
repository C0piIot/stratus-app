package dev.stratus.core.backup

/**
 * What the connection underneath costs, as far as the platform will say.
 *
 * A seam and not a call at the point of use, for the reason everything else
 * here is one: "do not back up on mobile data" is a decision, and decisions
 * belong where a test can reach them (stratus-app#133). The platform answers a
 * fact; [BackupRun] is what weighs it against the setting.
 */
fun interface Network {
    /**
     * Whether this connection is one somebody pays for by the byte.
     *
     * **True when the platform will not say**, which sounds like the dangerous
     * way round and is not: in practice the platforms decline to answer when
     * there is no connection at all, and parking a pass that had nothing to
     * send over costs nothing. Guessing the other way would spend somebody's
     * data plan on a question we could not answer.
     */
    suspend fun metered(): Boolean
}
