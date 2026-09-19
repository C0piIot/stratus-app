package dev.stratus.core.dav

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import dev.stratus.core.net.sourceBody
import kotlinx.io.RawSource

/**
 * A WebDAV client over standard verbs and nothing else.
 *
 * Nothing here is specific to Stratus, and that is a constraint rather than an
 * accident: the app has to work against any WebDAV server, so a feature that
 * would be easier with a private endpoint is a conversation and not a commit.
 * See CLAUDE.md.
 *
 * [baseUrl] is the collection the app is rooted at, such as
 * `http://host:8080/dav/`. Every [path] below is relative to it and given
 * decoded -- callers pass `/Photos/IMG 0001.jpg`, never the percent-encoded
 * form.
 */
class DavClient(
    private val http: HttpClient,
    baseUrl: String,
) {
    private val base: String = baseUrl.trimEnd('/')
    internal val basePath: String = Url(base).encodedPath.trimEnd('/')

    /**
     * The direct members of a collection.
     *
     * The entry for the collection itself is dropped. RFC 4918 says a `Depth: 1`
     * covers "the resource and its internal members", so a compliant server
     * sends it and Stratus now does too (stratus-backend#126). Filtering rather
     * than assuming it is there still earns its keep: a server that omits it is
     * out there, and this works against both without asking which it is.
     */
    suspend fun list(path: String): List<DavResource> {
        val self = normalise(path)
        return propfind(path, Depth.One).filterNot { normalise(it.path) == self }
    }

    /** One resource, or [DavError.NotFound]. */
    suspend fun stat(path: String): DavResource =
        propfind(path, Depth.Zero).firstOrNull() ?: throw DavError.NotFound(path)

    suspend fun propfind(path: String, depth: Depth): List<DavResource> {
        val response = http.request(url(path)) {
            method = PROPFIND
            header(HttpHeaders.Depth, depth.header)
            contentType(ContentType.Application.Xml)
            setBody(ALLPROP)
        }
        // 207 is the correct answer and what everything real sends. A plain 200
        // carrying a multistatus is tolerated because some servers do that and
        // the body is the part that matters.
        if (response.status.value == 207 || response.status.value == 200) {
            return MultiStatus.parse(response.bodyAsText(), basePath)
        }
        throw response.toError(path)
    }

    /**
     * Reads a file, optionally a byte range of it, and hands the bytes to
     * [block] as they arrive. The channel is only valid inside [block]: this
     * exists so a large video is never held in memory to be looked at.
     */
    suspend fun <T> read(
        path: String,
        range: LongRange? = null,
        block: suspend (ByteReadChannel) -> T,
    ): T = http.prepareGet(url(path)) {
        if (range != null) header(HttpHeaders.Range, "bytes=${range.first}-${range.last}")
    }.execute { response ->
        if (!response.status.isSuccess()) throw response.toError(path)
        block(response.bodyAsChannel())
    }

    /** Writes a file, replacing whatever was there. Returns the new ETag if the server gave one. */
    suspend fun put(path: String, body: ByteArray, contentType: String? = null): String? {
        val response = http.request(url(path)) {
            method = HttpMethod.Put
            if (contentType != null) header(HttpHeaders.ContentType, contentType)
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toError(path)
        return response.headers[HttpHeaders.ETag]?.removePrefix("W/")?.trim('"')?.ifEmpty { null }
    }

    /**
     * Writes a file from a stream, which is the one that matters.
     *
     * The [ByteArray] overload is for things small enough to hold; this is for
     * the videos the app exists to back up, where holding the body in memory to
     * send it is the difference between working and being killed for it.
     */
    suspend fun put(path: String, size: Long, body: RawSource, contentType: String? = null): String? {
        val response = http.request(url(path)) {
            method = HttpMethod.Put
            if (contentType != null) header(HttpHeaders.ContentType, contentType)
            setBody(sourceBody(size, body))
        }
        if (!response.status.isSuccess()) throw response.toError(path)
        return response.headers[HttpHeaders.ETag]?.removePrefix("W/")?.trim('"')?.ifEmpty { null }
    }

    /**
     * Renames or moves. [overwrite] false asks the server to refuse rather than
     * replace, which comes back as [DavError.PreconditionFailed].
     *
     * A directory with anything in it is refused by Stratus with a 409, which
     * surfaces as [DavError.Conflict] carrying whatever the server said. The
     * same status also means "the destination's parent does not exist", and no
     * server distinguishes them -- the caller knows which it asked for.
     */
    suspend fun move(from: String, to: String, overwrite: Boolean = false) {
        val response = http.request(url(from)) {
            method = MOVE
            header(HttpHeaders.Destination, url(to))
            header(HttpHeaders.Overwrite, if (overwrite) "T" else "F")
        }
        if (!response.status.isSuccess()) throw response.toError(from)
    }

    /** Deletes a file, or a directory and everything under it. */
    suspend fun delete(path: String) {
        val response = http.request(url(path)) { method = HttpMethod.Delete }
        if (!response.status.isSuccess()) throw response.toError(path)
    }

    /**
     * Creates a collection. A path that is already taken answers 405, which is
     * translated here because "method not allowed" describes the wire and not
     * what happened.
     */
    suspend fun makeCollection(path: String) {
        val response = http.request(url(path)) { method = MKCOL }
        if (response.status.value == 405) {
            throw DavError.Conflict(path, "something is already there")
        }
        if (!response.status.isSuccess()) throw response.toError(path)
    }

    private fun url(path: String): String = base + DavPath.encodePath(normalise(path))

    private fun normalise(path: String): String {
        val withLeading = if (path.startsWith("/")) path else "/$path"
        return if (withLeading.length > 1) withLeading.trimEnd('/') else withLeading
    }

    private suspend fun HttpResponse.toError(path: String): DavError = when (status.value) {
        401 -> DavError.Unauthorized()
        403 -> DavError.Forbidden(path)
        404 -> DavError.NotFound(path)
        405 -> DavError.MethodNotAllowed(path)
        409 -> DavError.Conflict(path, bodyAsText().trim().take(200))
        412 -> DavError.PreconditionFailed(path)
        else -> DavError.Unexpected(status.value, path)
    }

    private companion object {
        val PROPFIND = HttpMethod("PROPFIND")
        val MOVE = HttpMethod("MOVE")
        val MKCOL = HttpMethod("MKCOL")

        // allprop rather than a named list: a server may know properties we do
        // not ask about yet, and asking for everything costs the same round trip.
        const val ALLPROP = """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><allprop/></propfind>"""
    }
}
