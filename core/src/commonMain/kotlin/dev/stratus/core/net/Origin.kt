package dev.stratus.core.net

import io.ktor.http.Url

/**
 * The scheme, host and port of a base URL, with nothing after them.
 *
 * Everything Stratus serves that is not WebDAV hangs off the origin rather than
 * off `/dav/` -- tus at `/tus/`, a shared link at `/files/` -- so the one thing
 * they have in common is this, and it is worth having once.
 *
 * The default port is left off, so the result is what somebody would have typed.
 */
fun originOf(baseUrl: String): String = with(Url(baseUrl)) {
    val shown = if (port == protocol.defaultPort) "" else ":$port"
    "${protocol.name}://$host$shown"
}

/**
 * How a pin is keyed, from a base URL rather than a candidate.
 *
 * The same key as [Candidate.hostPort], and it has to stay the same: a pin
 * recorded while signing in is read back by everything that talks to the
 * instance afterwards.
 */
fun hostPortOf(baseUrl: String): String = with(Url(baseUrl)) { "$host:$port" }
