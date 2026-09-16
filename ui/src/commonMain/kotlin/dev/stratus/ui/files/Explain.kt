package dev.stratus.ui.files

import dev.stratus.core.dav.DavError
import dev.stratus.core.files.BrowserFailure

internal fun explain(failure: BrowserFailure): String = when (failure) {
    // The one that has to be said in these words. A generic error here reads as a
    // broken app rather than a server that cannot do this yet.
    is BrowserFailure.RenameNeedsAnEmptyFolder ->
        "The server cannot yet rename a folder that has anything in it, so " +
            "\"${failure.name}\" has to stay as it is for now."

    is BrowserFailure.Transfer -> "Could not fetch ${failure.name}."
    is BrowserFailure.Listing -> describe(failure.error)
    is BrowserFailure.Operation -> describe(failure.error)
}

private fun describe(error: DavError): String = when (error) {
    is DavError.Unauthorized -> "The server no longer accepts this sign-in."
    is DavError.Forbidden -> "Not allowed."
    is DavError.NotFound -> "That is no longer there."
    is DavError.Conflict -> "The server refused: ${error.detail}"
    is DavError.PreconditionFailed -> "Something else changed it first."
    is DavError.Malformed -> "The server's answer could not be read."
    is DavError.Unexpected -> "The server answered ${error.status}."
}
