package dev.stratus.core.backup

import kotlinx.io.RawSource

/** One file to put somewhere, and what the server needs to be told about it. */
data class UploadTarget(
    val path: String,
    val size: Long,
    val contentType: String?,
)

/** Where a previous attempt got to, when the transport can be told to continue. */
data class Resume(val handle: String?, val offset: Long)

sealed interface UploadOutcome {
    /** Arrived. [etag] when the server said what it stored, which is worth keeping. */
    data class Done(val etag: String?) : UploadOutcome

    /**
     * Stopped part way, and the server will accept the rest.
     *
     * Only a resumable transport produces this. A plain `PUT` that dies has
     * transferred nothing the server will admit to, so it reports [Failed].
     */
    data class Interrupted(val resume: Resume) : UploadOutcome

    data class Failed(val kind: FailureKind, val detail: String) : UploadOutcome
}

/**
 * Whether waiting helps.
 *
 * The distinction matters more than a retry count does: rejected credentials do
 * not improve by being asked again in an hour, and retrying them costs somebody
 * a lockout on a server that counts failed logins. A timeout does improve.
 */
enum class FailureKind { Transient, Permanent }

/**
 * How bytes get to a server.
 *
 * Shaped around "ask where you got to and continue" rather than around `PUT`,
 * even though `PUT` is the only implementation today. Stratus answers a tus
 * `PATCH` at the wrong offset with a 409 precisely because a client can believe
 * it is somewhere it is not, so a queue that cannot express resuming would have
 * to be rewritten the day tus arrives rather than extended.
 */
interface Transport {
    /** False for `PUT`, where an interruption means starting again. */
    val resumable: Boolean

    /**
     * Sends [target], continuing from [resume] when there is one.
     *
     * [open] is called with the offset to start from, so nothing reads bytes
     * that are already on the server.
     */
    suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome
}
