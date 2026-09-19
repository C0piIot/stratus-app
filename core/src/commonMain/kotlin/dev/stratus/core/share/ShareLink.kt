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
     * The URL to send somebody.
     *
     * [path] is as WebDAV gives it, leading slash and all; the server's own
     * paths have none, and the signature is over its form rather than ours.
     */
    fun link(path: String, isDirectory: Boolean, life: ShareLife, nowEpochSeconds: Long): String {
        val target = path.trim('/')
        val deadline = life.seconds?.let { nowEpochSeconds + it } ?: 0L
        return URLBuilder(originOf(baseUrl)).apply {
            appendPathSegments(listOf(FILES) + target.split('/'))
            parameters.append(PARAM, token(target, isDirectory, deadline))
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
        const val FILES = "files"
        const val PARAM = "k"
    }
}
