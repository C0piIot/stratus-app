package dev.stratus.core.store

import dev.stratus.core.net.Credentials
import dev.stratus.core.signin.Session

/**
 * One string holding a session and its password.
 *
 * Length-prefixed rather than delimited, because a password may legally contain
 * any character worth choosing as a separator -- a newline included -- and a
 * format that picks one corrupts that password silently, months later, on
 * somebody else's phone. Counting is in UTF-16 units, which is what `length` and
 * `substring` both use, so a character outside the basic plane survives.
 *
 * The leading version field costs four bytes and is what lets the format change
 * without the old value being mistaken for the new one.
 */
internal object SessionBlob {
    private const val VERSION = "1"

    fun encode(session: Session, credentials: Credentials): String =
        listOf(VERSION, session.baseUrl, credentials.username, credentials.password)
            .joinToString("") { "${it.length}:$it" }

    /** Null rather than an exception: an unreadable blob means "signed out". */
    fun decode(text: String): Pair<Session, Credentials>? {
        val fields = mutableListOf<String>()
        var at = 0
        while (at < text.length) {
            val colon = text.indexOf(':', at)
            if (colon < 0) return null
            val length = text.substring(at, colon).toIntOrNull() ?: return null
            if (length < 0) return null
            val start = colon + 1
            val end = start + length
            if (end > text.length) return null
            fields += text.substring(start, end)
            at = end
        }
        if (fields.size != 4 || fields[0] != VERSION) return null
        return Session(fields[1], fields[2]) to Credentials(fields[2], fields[3])
    }
}
