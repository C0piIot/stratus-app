package dev.stratus.core.backup

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

    /**
     * Given to the platform, which will say how it went later.
     *
     * A background `URLSession` transfers with the app suspended or killed and
     * relaunches it to report, so there is nothing to return and no coroutine
     * left to return it to (stratus-app#20). [ticket] is how the answer is
     * recognised when it arrives, through [UploadQueue.settle] -- possibly in
     * another life of the process.
     *
     * The transports here never produce it. It exists for the one that cannot
     * do anything else.
     */
    data class HandedOver(val ticket: String, val resume: Resume? = null) : UploadOutcome
}

/**
 * What the platform saw, before anybody decides what it means.
 *
 * A background transfer reports a status and whatever headers came with it,
 * and that is all it knows: the process that started it may be gone
 * (stratus-app#20). **Which of those is done, interrupted or failed is not
 * its judgement to make** -- the row the queue holds knows how long the file
 * is and which upload it belongs to, and the executor knows neither.
 */
data class TransferAnswer(
    /** The HTTP status, or null where the connection never produced one. */
    val status: Int?,
    /** Where the server says it got to, for a protocol that says. */
    val offset: Long? = null,
    val detail: String? = null,
)

/**
 * What [TransferAnswer] means for the upload it belongs to.
 *
 * Here rather than in the platform for the reason above, and in the open
 * rather than inside the queue so that a test can put every shape of answer
 * through it without a database.
 */
fun outcomeOf(upload: PendingUpload, answer: TransferAnswer): UploadOutcome = when {
    // No status at all: the connection went. For a resumable upload that is
    // progress to be carried on from, and for a `PUT` it is a restart, which
    // is what a `PUT` has always meant.
    answer.status == null -> UploadOutcome.Interrupted(Resume(upload.handle, answer.offset ?: upload.offset))

    answer.status !in 200..299 ->
        UploadOutcome.Failed(TusProtocol.kindOf(answer.status), answer.detail ?: "the server answered ${answer.status}")

    // tus has no "finish": the file is there once the upload is as long as it
    // said it would be, and short of that it is somewhere to carry on from.
    answer.offset != null && answer.offset < upload.size ->
        UploadOutcome.Interrupted(Resume(upload.handle, answer.offset))

    // No ETag to be had: tus does not report one and there is nowhere left to
    // ask. The cache takes a null, because what a rebuild walks is paths.
    else -> UploadOutcome.Done(null)
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
/**
 * The bytes of one upload, asked for the way the transport can take them.
 *
 * Two shapes because the two transports genuinely differ: one streams into a
 * request it is awaiting, and a background `URLSession` takes **a file and
 * nothing else**, because it transfers after this process is gone
 * (stratus-app#20). Handing the second one a stream would mean spooling it to
 * a file anyway, one level further from the platform that may already have
 * the bytes as one.
 */
interface UploadBody {
    /** A stream from [from] onwards. */
    suspend fun open(from: Long): RawSource

    /** The same bytes, at [toPath], which the caller then owns. */
    suspend fun writeTo(from: Long, toPath: String)
}

/**
 * A body that is only a stream, spooled where a file is wanted.
 *
 * The honest fallback for a source that cannot do better, and what every
 * in-process transport needs anyway.
 */
fun uploadBody(open: suspend (from: Long) -> RawSource): UploadBody = object : UploadBody {
    override suspend fun open(from: Long): RawSource = open(from)
    override suspend fun writeTo(from: Long, toPath: String) {
        open(from).use { source ->
            SystemFileSystem.sink(Path(toPath)).buffered().use { sink -> sink.transferFrom(source) }
        }
    }
}

interface Transport {
    /** False for `PUT`, where an interruption means starting again. */
    val resumable: Boolean

    /**
     * Sends [target], continuing from [resume] when there is one.
     *
     * [open] is called with the offset to start from, so nothing reads bytes
     * that are already on the server.
     */
    suspend fun send(target: UploadTarget, resume: Resume?, body: UploadBody): UploadOutcome
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
