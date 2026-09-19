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
    // The dead end worth naming: some servers show a folder they will not let
    // anybody list, because what is inside lives under a longer path. Left as
    // "the server answered 405" it reads as the app being broken.
    is BrowserFailure.Listing -> when (failure.error) {
        is DavError.MethodNotAllowed ->
            "This server will not list this folder. It may keep what is inside under a longer path."

        else -> describe(failure.error)
    }
    is BrowserFailure.Operation -> describe(failure.error)
}

private fun describe(error: DavError): String = when (error) {
    is DavError.Unauthorized -> "The server no longer accepts this sign-in."
    is DavError.Forbidden -> "Not allowed."
    is DavError.NotFound -> "That is no longer there."
    is DavError.MethodNotAllowed -> "The server does not allow that here."
    is DavError.Conflict -> "The server refused: ${error.detail}"
    is DavError.PreconditionFailed -> "Something else changed it first."
    is DavError.Malformed -> "The server's answer could not be read."
    is DavError.Unexpected -> "The server answered ${error.status}."
}
