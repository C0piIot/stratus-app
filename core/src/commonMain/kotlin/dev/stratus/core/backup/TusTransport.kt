package dev.stratus.core.backup

import dev.stratus.core.net.sourceBody
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.RawSource

/**
 * Resumable upload over tus, the negotiated upgrade to a plain `PUT`.
 *
 * `PUT` is all or nothing: a four-gigabyte video on a mobile connection restarts
 * from zero on every drop, forever. This is the difference between a backup that
 * finishes and one that does not.
 *
 * Written against a real server rather than the specification alone, and three
 * of its answers decide the shape below:
 *
 * - **There is no "finish".** The file appears when the upload is as long as it
 *   said it would be, so done is `offset == size` and a later `HEAD` answering
 *   404 means *already there*, not *lost*.
 * - **A `PATCH` at the wrong offset is a 409**, which is exactly what a retry
 *   after a timeout produces. So resuming asks the server where it got to and
 *   never trusts what was recorded locally.
 * - **An upload expires after twelve hours**, which also surfaces as a 404 and is
 *   handled by the same path: begin again.
 */
class TusTransport(
    private val http: HttpClient,
    private val endpoint: String,
    /** The ETag the file ends up with, which tus itself does not report. */
    private val etagOf: suspend (path: String) -> String?,
) : Transport {

    override val resumable = true

    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome = try {
        val existing = resume?.handle
        if (existing == null) {
            begin(target)?.let { append(target, it, 0, open) }
                ?: UploadOutcome.Failed(FailureKind.Transient, "the server would not start the upload")
        } else {
            when (val offset = offsetOf(existing)) {
                // Gone: either it finished and the file is there, or twelve hours
                // passed. The ETag tells the two apart without guessing.
                null -> etagOf(target.path)
                    ?.let { UploadOutcome.Done(it) }
                    ?: UploadOutcome.Interrupted(Resume(null, 0))

                else -> if (offset >= target.size) {
                    UploadOutcome.Done(etagOf(target.path))
                } else {
                    append(target, existing, offset, open)
                }
            }
        }
    } catch (e: Exception) {
        UploadOutcome.Failed(FailureKind.Transient, e.message ?: "no detail")
    }

    /** Creates the upload and returns where to send it, from the `Location`. */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun begin(target: UploadTarget): String? {
        val response = http.request(endpoint) {
            method = HttpMethod.Post
            header(TUS_VERSION_HEADER, TUS_VERSION)
            header("Upload-Length", target.size.toString())
            // The destination is a path, and its folder has to exist -- which is
            // why the queue makes the month's directory before every attempt.
            header("Upload-Metadata", "filename " + Base64.encode(target.path.trimStart('/').encodeToByteArray()))
        }
        if (response.status.value != 201) return null
        return response.headers[HttpHeaders.Location]?.let { absolute(it) }
    }

    /** Where the server says it got to, or null if the upload is no longer there. */
    private suspend fun offsetOf(handle: String): Long? {
        val response = http.request(handle) {
            method = HttpMethod.Head
            header(TUS_VERSION_HEADER, TUS_VERSION)
        }
        if (!response.status.isTus()) return null
        return response.headers[UPLOAD_OFFSET]?.toLongOrNull()
    }

    /**
     * Sends everything that is left in one request.
     *
     * Not chunked: if it stops at three point nine gigabytes the server has
     * three point nine gigabytes and a `HEAD` will say so. Splitting it here
     * would add client-side state that can disagree with the server, which is
     * the one thing this protocol exists to avoid.
     */
    private suspend fun append(
        target: UploadTarget,
        handle: String,
        from: Long,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome {
        val response = http.request(handle) {
            method = HttpMethod.Patch
            header(TUS_VERSION_HEADER, TUS_VERSION)
            header(UPLOAD_OFFSET, from.toString())
            header(HttpHeaders.ContentType, OFFSET_OCTET_STREAM)
            setBody(sourceBody(target.size - from, open(from)))
        }

        // A conflict means the server is somewhere else than we thought, which a
        // timed-out retry produces. Ask rather than argue.
        if (response.status.value == 409) {
            return UploadOutcome.Interrupted(Resume(handle, offsetOf(handle) ?: 0))
        }
        if (!response.status.isTus()) {
            return UploadOutcome.Failed(kindOf(response.status.value), "tus answered ${response.status.value}")
        }

        val reached = response.headers[UPLOAD_OFFSET]?.toLongOrNull() ?: from
        return if (reached >= target.size) {
            UploadOutcome.Done(etagOf(target.path))
        } else {
            UploadOutcome.Interrupted(Resume(handle, reached))
        }
    }

    /** A `Location` may be a path; everything after it has to be absolute. */
    private fun absolute(location: String): String =
        if (location.startsWith("http")) location else Url(endpoint).let { "${it.protocol.name}://${it.host}" + portOf(it) + location }

    private fun portOf(url: Url) = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"

    private fun kindOf(status: Int) =
        if (status == 401 || status == 403) FailureKind.Permanent else FailureKind.Transient

    private fun io.ktor.http.HttpStatusCode.isSuccessful() = value in 200..299

    private fun io.ktor.http.HttpStatusCode.isTus() = isSuccessful()

    private companion object {
        const val TUS_VERSION_HEADER = "Tus-Resumable"
        const val TUS_VERSION = "1.0.0"
        const val UPLOAD_OFFSET = "Upload-Offset"
        const val OFFSET_OCTET_STREAM = "application/offset+octet-stream"
    }
}

/**
 * Whether this server speaks tus, and where.
 *
 * Asked with the protocol's own `OPTIONS`, so nothing here is specific to
 * Stratus: any tus server answers with `Tus-Resumable`. Asked once per pass
 * rather than remembered, because a server that gains tus tomorrow should be
 * used tomorrow, with nothing to invalidate.
 */
suspend fun negotiateTus(http: HttpClient, baseUrl: String): String? {
    val url = Url(baseUrl)
    val port = if (url.port == url.protocol.defaultPort) "" else ":${url.port}"
    val endpoint = "${url.protocol.name}://${url.host}$port/tus/"
    return try {
        val response: HttpResponse = http.request(endpoint) { method = HttpMethod.Options }
        if (response.headers["Tus-Resumable"] != null) endpoint else null
    } catch (_: Exception) {
        null
    }
}
