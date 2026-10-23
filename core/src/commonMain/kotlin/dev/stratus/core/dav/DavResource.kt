package dev.stratus.core.dav

/**
 * One entry from a PROPFIND, with its path already decoded.
 *
 * [path] is server-relative and free of both percent-encoding and XML escaping,
 * so it is the name a person would recognise rather than the one the wire
 * carried. Everything else is optional because a server is allowed to answer
 * with whichever properties it has: a collection typically reports only that it
 * is one.
 */
data class DavResource(
    val path: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val contentType: String? = null,
    val etag: String? = null,
    val lastModifiedEpochMs: Long? = null,
    /**
     * Where a file in one of Stratus's generated collections -- `/photos/`,
     * `/music/` -- really is, in the same form as [path]: the only address a
     * link can be signed for (stratus-backend#431). Null everywhere else,
     * including on every other server.
     */
    val source: String? = null,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** The `Depth` header, which is the difference between one entry and a listing. */
enum class Depth(val header: String) {
    Zero("0"),
    One("1"),
}
