package dev.stratus.core.instance

import dev.stratus.core.net.Credentials

/**
 * One instance and its password as a single string.
 *
 * Length-prefixed rather than delimited, because a password may legally contain
 * any character worth choosing as a separator -- a newline included -- and a
 * format that picks one corrupts that password silently, months later, on
 * somebody else's phone. Counting is in UTF-16 units, which is what `length` and
 * `substring` both use, so a character outside the basic plane survives.
 */
internal object InstanceBlob {
    private const val VERSION = "4"

    fun encode(instance: Instance, credentials: Credentials): String =
        listOf(
            VERSION,
            instance.id,
            instance.baseUrl,
            credentials.username,
            credentials.password,
            instance.backupRoot,
            if (instance.backupEnabled) "1" else "0",
            // Newline-joined: a bucket id and an album identifier have none.
            instance.sources.joinToString("\n"),
        ).joinToString("") { "${it.length}:$it" }

    /** Null rather than an exception: an unreadable blob means "sign in again". */
    fun decode(text: String): Pair<Instance, Credentials>? {
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
        if (fields.size != 8 || fields[0] != VERSION) return null
        return Instance(
            id = fields[1],
            baseUrl = fields[2],
            username = fields[3],
            backupRoot = fields[5],
            backupEnabled = fields[6] == "1",
            sources = fields[7].split("\n").filter { it.isNotBlank() }.toSet(),
        ) to Credentials(fields[3], fields[4])
    }
}
