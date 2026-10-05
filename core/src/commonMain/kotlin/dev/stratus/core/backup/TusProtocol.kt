package dev.stratus.core.backup

import dev.stratus.core.net.originOf
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod

/**
 * The small half of tus: starting an upload and asking where it got to.
 *
 * Out here because **two transports need the same answers and must not grow
 * two ideas of what tus is** (stratus-app#20). The in-process one does the
 * whole protocol itself; the background one does these two and hands the body
 * to the system, which cannot answer where it was asked.
 *
 * That the split falls here is not a coincidence. `TusTransport.append` was
 * already written to send *everything that is left in one request*, so there
 * was never any chunking to take apart: the control plane is these two
 * requests, and the bytes are the third.
 */
class TusProtocol(private val http: HttpClient, val endpoint: String) {

    /** Creates the upload and answers where it lives, or null if the server refused. */
    suspend fun begin(target: UploadTarget): String? {
        val response = http.request(endpoint) {
            method = HttpMethod.Post
            header(VERSION_HEADER, VERSION)
            header("Upload-Length", target.size.toString())
            // The destination is a path, and its folder has to exist -- which is
            // why the queue makes the month's directory before every attempt.
            header("Upload-Metadata", metadataOf(target))
        }
        if (response.status.value != 201) return null
        return response.headers[HttpHeaders.Location]?.let(::absolute)
    }

    /** Where the server says it got to, or null if the upload is no longer there. */
    suspend fun offsetOf(handle: String): Long? {
        val response = http.request(handle) {
            method = HttpMethod.Head
            header(VERSION_HEADER, VERSION)
        }
        if (response.status.value !in 200..299) return null
        return response.headers[UPLOAD_OFFSET]?.toLongOrNull()
    }

    /** A `Location` may be a path; everything after it has to be absolute. */
    fun absolute(location: String): String =
        if (location.startsWith("http")) location else originOf(endpoint) + location

    companion object {
        const val VERSION_HEADER = "Tus-Resumable"
        const val VERSION = "1.0.0"
        const val UPLOAD_OFFSET = "Upload-Offset"
        const val OFFSET_OCTET_STREAM = "application/offset+octet-stream"

        /** Rejected credentials do not improve by being asked again. */
        fun kindOf(status: Int) =
            if (status == 401 || status == 403) FailureKind.Permanent else FailureKind.Transient
    }
}
