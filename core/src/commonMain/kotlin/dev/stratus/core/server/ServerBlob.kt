package dev.stratus.core.server

import dev.stratus.core.net.Credentials

/**
 * The server and its password as a single string.
 *
 * Length-prefixed rather than delimited, because a password may legally contain
 * any character worth choosing as a separator -- a newline included -- and a
 * format that picks one corrupts that password silently, months later, on
 * somebody else's phone. Counting is in UTF-16 units, which is what `length` and
 * `substring` both use, so a character outside the basic plane survives.
 */
internal object ServerBlob {
    // 5 dropped the generated id and the separate backup flag (stratus-app#131).
    // 6 added "only on wifi" (stratus-app#133).
    //
    // A 5 is still read, and that is the rule worth keeping rather than the
    // version number: signing somebody out costs them the record of what has
    // been backed up, so the next pass walks the whole server to rebuild it.
    // Paying that for a field with a default would be absurd. A shape with no
    // sensible default is what does not decode.
    private const val VERSION = "6"
    private const val WITHOUT_WIFI_SETTING = "5"

    fun encode(instance: Server, credentials: Credentials): String =
        listOf(
            VERSION,
            instance.baseUrl,
            credentials.username,
            credentials.password,
            instance.backupRoot,
            // Newline-joined: a bucket id and an album identifier have none.
            instance.sources.joinToString("\n"),
            if (instance.onlyOnWifi) "1" else "0",
        ).joinToString("") { "${it.length}:$it" }

    /** Null rather than an exception: an unreadable blob means "sign in again". */
    fun decode(text: String): Pair<Server, Credentials>? {
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
        val readable = (fields.size == 7 && fields[0] == VERSION) ||
            (fields.size == 6 && fields[0] == WITHOUT_WIFI_SETTING)
        if (!readable) return null
        return Server(
            baseUrl = fields[1],
            username = fields[2],
            backupRoot = fields[4],
            sources = fields[5].split("\n").filter { it.isNotBlank() }.toSet(),
            // A record written before the setting existed takes the default,
            // which is the safe half: a phone that was backing up over mobile
            // data stops doing so rather than carrying on unasked.
            onlyOnWifi = fields.getOrNull(6)?.let { it == "1" } ?: true,
        ) to Credentials(fields[2], fields[3])
    }
}
