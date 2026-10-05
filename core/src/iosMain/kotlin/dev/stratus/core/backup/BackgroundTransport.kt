package dev.stratus.core.backup

import io.ktor.client.HttpClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.RawSource
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue

/**
 * Uploads that outlive the app (stratus-app#20).
 *
 * The one transport that cannot answer where it is asked: it hands the body to
 * the system and returns [UploadOutcome.HandedOver], and the answer arrives
 * through `UploadQueue.settle` whenever the system gets round to saying --
 * possibly in another life of the process.
 *
 * **tus with the protocol half still in process.** Creating the upload and
 * asking where it got to are small requests that answer at once, so they stay
 * Ktor and stay shared through [TusProtocol]; only the body goes to the
 * session. That split is not an invention here -- `TusTransport.append`
 * already sent everything that was left in one request, so there was never any
 * chunking to take apart.
 */
@OptIn(ExperimentalForeignApi::class)
class BackgroundTusTransport(
    http: HttpClient,
    endpoint: String,
    private val uploads: BackgroundUploads,
    private val credentials: String?,
    /** Puts the bytes from an offset where the session can read them. */
    private val spool: suspend (from: Long, toPath: String) -> Unit,
) : Transport {

    private val tus = TusProtocol(http, endpoint)

    override val resumable = true

    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome {
        // Where the server is, asked rather than assumed -- the same rule the
        // in-process transport follows, and for the same reason: a client can
        // believe it is somewhere the server does not agree with.
        val handle = resume?.handle
        val (at, from) = if (handle == null) {
            (tus.begin(target) ?: return UploadOutcome.Failed(
                FailureKind.Transient,
                "the server would not start the upload",
            )) to 0L
        } else {
            val offset = tus.offsetOf(handle)
                // Gone: it finished, or twelve hours passed. Either way the
                // next pass begins again, which is what the in-process one
                // does with the same answer.
                ?: return UploadOutcome.Interrupted(Resume(null, 0))
            if (offset >= target.size) return UploadOutcome.Done(null)
            handle to offset
        }

        val request = NSMutableURLRequest.requestWithURL(NSURL(string = at)).apply {
            setHTTPMethod("PATCH")
            setValue(TusProtocol.VERSION, forHTTPHeaderField = TusProtocol.VERSION_HEADER)
            setValue(from.toString(), forHTTPHeaderField = TusProtocol.UPLOAD_OFFSET)
            setValue(TusProtocol.OFFSET_OCTET_STREAM, forHTTPHeaderField = "Content-Type")
            // The session runs outside this process, so it cannot ask a Ktor
            // plugin for credentials: they go on the request or nowhere.
            credentials?.let { setValue(it, forHTTPHeaderField = "Authorization") }
        }

        val ticket = uploads.start(request) { body -> spool(from, body.toString()) }
        // The handle travels with the handover: the answer arrives in another
        // process, and the row is where it will look for where to carry on.
        return UploadOutcome.HandedOver(ticket, Resume(at, from))
    }
}

/**
 * The same, for a server that does not speak tus.
 *
 * One task, the whole file, and no resuming -- which is what a `PUT` has
 * always meant. It exists so that a Nextcloud on an iPhone still backs up in
 * the background: without it the promise of working against any WebDAV server
 * would hold everywhere except where it matters most.
 */
@OptIn(ExperimentalForeignApi::class)
class BackgroundPutTransport(
    private val baseUrl: String,
    private val uploads: BackgroundUploads,
    private val credentials: String?,
    private val spool: suspend (from: Long, toPath: String) -> Unit,
) : Transport {

    override val resumable = false

    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome {
        val url = baseUrl.trimEnd('/') + "/" + target.path.trimStart('/')
        val request = NSMutableURLRequest.requestWithURL(NSURL(string = url)).apply {
            setHTTPMethod("PUT")
            target.contentType?.let { setValue(it, forHTTPHeaderField = "Content-Type") }
            credentials?.let { setValue(it, forHTTPHeaderField = "Authorization") }
        }
        val ticket = uploads.start(request) { body -> spool(0, body.toString()) }
        return UploadOutcome.HandedOver(ticket)
    }
}
