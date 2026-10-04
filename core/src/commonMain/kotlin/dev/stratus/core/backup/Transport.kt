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

/**
 * tus where the server offers it, `PUT` where it does not.
 *
 * Asked every pass rather than remembered: a server that gains tus tomorrow
 * should be used tomorrow, and there is nothing to invalidate.
 *
 * Out here rather than inside `Backup` because **which way a photograph goes
 * is a decision**, and a decision that only the composition root can build is
 * one no test reaches. It cost this: every conformance suite named a
 * transport by hand, so the combination production actually takes -- the base
 * at the origin, the path browsed, tus negotiated -- was proved by nothing
 * faster than an emulator (stratus-backend#285).
 *
 * [baseUrl] is only read for its origin: the tus endpoint is a sibling of the
 * tree rather than a corner of it.
 */
suspend fun transportFor(connection: Connection, baseUrl: String): Transport =
    negotiateTus(connection.http, baseUrl)
        ?.let { endpoint ->
            // tus reports no ETag, and the ETag is what makes a later check a
            // real check. One request per file buys it back.
            TusTransport(connection.http, endpoint) { path ->
                runCatching { connection.dav.stat(path).etag }.getOrNull()
            }
        }
        ?: PutTransport(connection.dav)
