package dev.stratus.core.net

/** One thing to try: a scheme, an origin and a path. */
data class Candidate(
    val scheme: Scheme,
    val host: String,
    val port: Int,
    val path: String,
) {
    /** The default port is left off, so a URL looks like one somebody would type. */
    val origin: String
        get() = "${scheme.wire}://$host" + if (port == scheme.defaultPort) "" else ":$port"

    val baseUrl: String get() = origin + path

    /** How a pin or a remembered consent is keyed: a different port is a different server. */
    val hostPort: String get() = "$host:$port"
}

/**
 * What to try over [scheme], most likely first.
 *
 * A typed path is taken at its word -- somebody who wrote one knows something we
 * do not. Otherwise **the root first**: a Stratus is one WebDAV namespace from
 * its origin (stratus-backend#279), so the root is both where our own server
 * lives and where a server somebody pointed at a directory usually is. Then
 * `/dav/`, which stays in the list as somebody else's convention -- Apache and
 * lighttpd document it -- and not as compatibility with anything of ours.
 *
 * Guesses, not knowledge: a server that answers at neither is a server whose
 * path somebody has to type, which is what [ServerAddress.path] is for.
 */
fun ServerAddress.candidates(scheme: Scheme): List<Candidate> {
    val paths = path?.let { listOf(it) } ?: listOf("/", "/dav/")
    val resolvedPort = port ?: scheme.defaultPort
    return paths.map { Candidate(scheme, host, resolvedPort, it) }
}
