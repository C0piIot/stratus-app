package dev.stratus.core.dav

/**
 * What went wrong, classified rather than left as a status code.
 *
 * Deliberately not one exception per status: the useful distinction is what the
 * caller can do about it, and a screen showing "409" to somebody renaming a
 * folder has failed them. The statuses behind each of these were measured
 * against a real Stratus rather than read off the RFC, because servers differ.
 */
sealed class DavError(message: String) : Exception(message) {

    /** The credentials were rejected, or none were offered. */
    class Unauthorized : DavError("the server rejected these credentials")

    /** Authenticated, but not allowed to do this. */
    class Forbidden(val path: String) : DavError("not allowed: $path")

    class NotFound(val path: String) : DavError("no such path: $path")

    /**
     * The request cannot apply to the tree as it stands.
     *
     * One status covering two genuinely different situations, and the server
     * cannot tell them apart for us: a write whose parent directory does not
     * exist, and a move of a directory that still has something in it. The
     * caller knows which it asked for, so [detail] carries whatever the server
     * said and the decision of how to phrase it belongs upstairs.
     */
    class Conflict(val path: String, val detail: String) : DavError("conflict at $path: $detail")

    /** An `If-Match` or an `Overwrite: F` that the current state did not satisfy. */
    class PreconditionFailed(val path: String) : DavError("the precondition failed for $path")

    /** The answer was not something this client can read. */
    class Malformed(val detail: String) : DavError("the server's answer could not be read: $detail")

    class Unexpected(val status: Int, val path: String) : DavError("unexpected $status for $path")
}
