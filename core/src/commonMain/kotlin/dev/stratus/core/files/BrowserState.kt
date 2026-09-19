package dev.stratus.core.files

import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource

data class BrowserState(
    val path: String = "/",
    val entries: List<DavResource> = emptyList(),
    val busy: Boolean = false,
    val failure: BrowserFailure? = null,
    val pending: Confirmation? = null,
)

/**
 * Something the app will not do without being asked twice.
 *
 * Held as state rather than left to a dialog with a mind of its own, so that
 * "does deleting ask first" is a question a test can answer.
 */
sealed interface Confirmation {
    data class Delete(val target: DavResource) : Confirmation
    data class Rename(val target: DavResource) : Confirmation

    /**
     * Not a confirmation so much as a choice of how long, but it belongs here:
     * it is the same one-thing-at-a-time slot, and what it gives away cannot be
     * taken back either.
     */
    data class Share(val target: DavResource) : Confirmation
}

/**
 * What went wrong, classified where the context to classify it exists.
 *
 * [RenameNeedsAnEmptyFolder] is the one that earns this type. The server answers
 * a plain 409 whether the destination's parent is missing or the folder being
 * moved still has something in it, and only the caller knows which it asked for.
 */
sealed interface BrowserFailure {
    data class Listing(val error: DavError) : BrowserFailure
    data class RenameNeedsAnEmptyFolder(val name: String) : BrowserFailure
    data class Operation(val error: DavError) : BrowserFailure
    data class Transfer(val name: String) : BrowserFailure

    /**
     * The link was signed and the server did not know what it was.
     *
     * A signed link is Stratus's, and this app works against any WebDAV server,
     * so this is the ordinary answer from the others rather than a fault.
     */
    data object TheServerDoesNotDoLinks : BrowserFailure
}
