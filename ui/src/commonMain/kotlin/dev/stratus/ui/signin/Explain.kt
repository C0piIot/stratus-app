package dev.stratus.ui.signin

import dev.stratus.core.net.AddressProblem
import dev.stratus.core.signin.SignInFailure

/**
 * Failures in words, and the only reason the failure type is as detailed as it
 * is: each of these suggests a different next move.
 */
internal fun explain(reason: SignInFailure): String = when (reason) {
    is SignInFailure.Unreachable ->
        "Could not reach ${reason.host}. Check the address, and that this device is on the same network."

    is SignInFailure.WrongCredentials ->
        "${reason.host} did not accept that username and password."

    // The distinction that earns its keep: a server is there, so the address is
    // right and only the path is wrong. Telling somebody to check the address
    // here would send them looking in the wrong place. The second sentence is
    // the one that helps, and it is only true in the second case: a server that
    // answers something -- a web page, a 405 -- at the address somebody typed is
    // usually one whose files are published somewhere else entirely.
    is SignInFailure.NotWebDav -> {
        val paths = reason.tried.joinToString(" or ") { it.path }
        if (reason.tried.all { it.status == 404 }) {
            "Nothing is published at $paths on ${reason.origin}. Type the full path to your files."
        } else {
            "${reason.origin} answered, but there is no WebDAV at $paths. A server's WebDAV " +
                "address is often not the one its web interface shows -- type the full path it documents."
        }
    }

    is SignInFailure.PlaintextRefused ->
        "Cancelled. Nothing was sent to ${reason.host}."

    SignInFailure.UsernameUnusable ->
        "A username cannot contain a colon."

    is SignInFailure.Address -> when (val problem = reason.problem) {
        AddressProblem.Empty -> "Type the address of your server."
        is AddressProblem.UnknownScheme -> "${problem.typed} is not an address this app can open."
        is AddressProblem.BadPort -> "\"${problem.typed}\" is not a port number."
        AddressProblem.CredentialsInUrl -> "Put the username and password in their own fields, not in the address."
        AddressProblem.QueryOrFragment -> "That looks like a link to a page. Use the address of the server itself."
        is AddressProblem.NotAHost -> "\"${problem.typed}\" is not an address."
    }
}
