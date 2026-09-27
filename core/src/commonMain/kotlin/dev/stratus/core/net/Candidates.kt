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
 * do not. Otherwise `/dav/` first, because it is where our own server lives, then
 * the root, which is where a server somebody pointed at a directory usually is.
 */
fun ServerAddress.candidates(scheme: Scheme): List<Candidate> {
    val paths = path?.let { listOf(it) } ?: listOf("/dav/", "/")
    val resolvedPort = port ?: scheme.defaultPort
    return paths.map { Candidate(scheme, host, resolvedPort, it) }
}
