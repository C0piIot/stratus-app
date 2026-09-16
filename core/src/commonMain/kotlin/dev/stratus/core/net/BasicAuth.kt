package dev.stratus.core.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class Credentials(val username: String, val password: String) {
    // A data class prints every field, and this one would print it into a log or
    // a crash report the first time somebody was careless.
    override fun toString(): String = "Credentials(username=$username, password=***)"
}

/**
 * RFC 7617, in four lines rather than a dependency.
 *
 * Ktor's auth plugin would do this reactively by default -- wait for a 401, then
 * replay -- which doubles every request in an app whose job is thousands of them.
 *
 * The bytes are UTF-8, which is what the RFC's `charset` parameter asks for and
 * what Go's `net/http` reads on the other end. An implementation that reached for
 * Latin-1 would work until somebody's password had an "ñ" in it.
 */
@OptIn(ExperimentalEncodingApi::class)
fun basicAuthHeader(credentials: Credentials): String =
    "Basic " + Base64.encode("${credentials.username}:${credentials.password}".encodeToByteArray())

/**
 * Whether these credentials can be expressed at all.
 *
 * A colon in the username is unrepresentable: the server splits on the first one
 * and would read the rest as the password. Better refused at the keyboard than
 * turned into a login failure nobody can explain.
 */
fun usernameIsUsable(username: String): Boolean = ':' !in username
