package dev.stratus.core.share

import dev.stratus.core.crypto.hmacSha256
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.originOf
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** How long a link lives. The four the server's own form offers. */
enum class ShareLife(val seconds: Long?) {
    ADay(86_400),
    AWeek(7 * 86_400),
    AMonth(30 * 86_400),

    /** Until the password changes, which is the only other way one ends. */
    Forever(null),
}

/**
 * Links a person without an account can open, signed here rather than asked for.
 *
 * The server stores nothing and derives its signing key from the password
 * (stratus-backend#169), and this app already holds that password -- so it can
 * mint its own links and no endpoint has to be invented for it. That is the
 * difference between sharing and a private API.
 *
 * The format is the server's and has to stay byte for byte its equal, which is
 * not something a unit test can promise: `ShareConformanceTest` signs one here
 * and opens it there.
 */
@OptIn(ExperimentalEncodingApi::class)
class ShareLinks(private val baseUrl: String, credentials: Credentials) {

    private val owner = credentials.username

    // The same derivation as the session cookie's, with a context string of its
    // own, so neither can ever be read as the other though one password is
    // behind both.
    private val key = hmacSha256(
        credentials.password.encodeToByteArray(),
        (CONTEXT + owner).encodeToByteArray(),
    )

    /**
     * The URL to send somebody, with a signature on it, or null when the path
     * is not one the server can sign for.
     *
     * There is one address now: the WebDAV path and the browser's are the same
     * URL (stratus-backend#279), so there is nothing to choose between and
     * nothing to derive -- this is the URL the browser is already looking at,
     * with `?k=` on the end.
     *
     * [path] is as WebDAV gives it, leading slash and all.
     */
    fun link(path: String, isDirectory: Boolean, life: ShareLife, nowEpochSeconds: Long): String? {
        val target = signable(path) ?: return null
        val deadline = life.seconds?.let { nowEpochSeconds + it } ?: 0L
        return URLBuilder(baseUrl).apply {
            appendPathSegments(path.trim('/').split('/'))
            parameters.append(PARAM, token(target, isDirectory, deadline))
        }.buildString()
    }

    /**
     * What a signature is over: the path **inside the files collection**, with
     * the collection's own name off the front.
     *
     * This is the one place in this app that knows the shape of a Stratus, and
     * it is here because there is nowhere else it could be. The base URL is
     * the origin, so a WebDAV path is `/files/holiday/x.jpg`; the server signs
     * the row, which is `holiday/x.jpg`, and a token over anything else is a
     * 403 nobody could explain from the outside.
     *
     * Null for a path outside the collection. The generated ones --
     * `/photos/`, `/playlists/` -- have no rows behind them and no gate that
     * verifies a signature, so a link to one would be refused however it was
     * signed. Saying so here is what keeps the button from being offered and
     * broken.
     */
    private fun signable(path: String): String? {
        val trimmed = path.trim('/')
        if (trimmed == FILES) return ""
        return if (trimmed.startsWith("$FILES/")) trimmed.removePrefix("$FILES/") else null
    }

    /**
     * The URL of the server's own rendering of a picture, signed the same way.
     *
     * The same token as [link] -- the signature is over the path, and the
     * thumbnail lives behind the same gate as the file -- with a size on it.
     * It is what makes a HEIC something a television or a gallery can show
     * without decoding it here.
     *
     * This one hangs off the origin and names the row's path, because there
     * is no thumbnail in WebDAV and `/thumb/` is where the server's own
     * rendering lives. Null for the same paths [link] refuses, and for the
     * same reason.
     */
    fun thumbnail(path: String, width: Int, life: ShareLife, nowEpochSeconds: Long): String? {
        val target = signable(path) ?: return null
        val deadline = life.seconds?.let { nowEpochSeconds + it } ?: 0L
        return URLBuilder(originOf(baseUrl)).apply {
            appendPathSegments(listOf(THUMBNAILS) + target.split('/'))
            parameters.append(SIZE, width.toString())
            parameters.append(PARAM, token(target, isDirectory = false, deadline = deadline))
        }.buildString()
    }

    /**
     * `k1.<owner>.<path>.<f|d>.<deadline>.<signature>`, the three middle parts
     * base64url without padding, and the signature over everything before it --
     * the version included, so a token of an older shape cannot be read as a
     * newer one.
     */
    private fun token(target: String, isDirectory: Boolean, deadline: Long): String {
        val payload = listOf(
            VERSION,
            encode(owner.encodeToByteArray()),
            encode(target.encodeToByteArray()),
            if (isDirectory) SUBTREE else FILE,
            deadline.toString(),
        ).joinToString(".")
        return payload + "." + encode(hmacSha256(key, payload.encodeToByteArray()))
    }

    // Go's RawURLEncoding: the padded one with the padding taken off.
    private fun encode(bytes: ByteArray) = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private companion object {
        const val CONTEXT = "stratus share link v1\u0000"
        const val VERSION = "k1"
        const val FILE = "f"
        const val SUBTREE = "d"
        const val THUMBNAILS = "thumb"

        /** The collection the writable tree is, as the server names it. */
        const val FILES = "files"
        const val SIZE = "size"
        const val PARAM = "k"
    }
}
