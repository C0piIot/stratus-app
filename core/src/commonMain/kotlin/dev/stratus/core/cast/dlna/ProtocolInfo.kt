package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastItem
import dev.stratus.core.cast.HLS_TYPE

/**
 * One line of what a renderer says it plays: `http-get:*:video/mp4:*`.
 *
 * The four fields are the protocol, the network, the content type and a
 * fourth that DLNA fills with profile names and flags. Only the first and the
 * third decide anything here: a renderer that lists `video/mp4` is sent an
 * MP4, and whether it likes the profile inside is the renderer's to say, the
 * way a Chromecast reports a codec it cannot decode.
 */
data class ProtocolInfo(val protocol: String, val network: String, val contentType: String, val additional: String) {
    override fun toString() = "$protocol:$network:$contentType:$additional"

    /** Whether a file of [type] fetched over http fits this line. */
    fun takes(type: String): Boolean {
        if (protocol != "http-get") return false
        val mine = canonical(contentType)
        val theirs = canonical(type)
        return mine == "*" || mine == theirs || (mine.endsWith("/*") && theirs.startsWith(mine.dropLast(1)))
    }

    companion object {
        /**
         * The `Sink` of `GetProtocolInfo`, a comma-separated list. A line that
         * does not have four fields is dropped rather than failing the list:
         * renderers ship with typos in it, and the other two hundred lines are
         * still true.
         */
        fun parseList(sink: String): List<ProtocolInfo> = sink.split(',').mapNotNull { line ->
            val parts = line.trim().split(':', limit = 4)
            if (parts.size != 4) null else ProtocolInfo(parts[0], parts[1], parts[2], parts[3])
        }

        /**
         * A type with its parameters off and its aliases folded. A playlist
         * has four names in the wild, and a renderer that lists one of them
         * plays the others.
         */
        private fun canonical(type: String): String {
            val bare = type.substringBefore(';').trim().lowercase()
            return if (bare in HLS_ALIASES) HLS_TYPE else bare
        }

        private val HLS_ALIASES = setOf(
            HLS_TYPE,
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
        )
    }
}

/** What to send a renderer, and the line to declare it with. */
data class Pick(val item: CastItem, val protocolInfo: String)

/**
 * The first of [choices] the renderer says it plays, in the order they are
 * given -- which is the order of preference, so HLS before the file it was
 * made from. Null when it plays none of them, which is said to somebody
 * rather than sent: a renderer handed what it cannot play shows a black
 * screen and reports nothing, and nobody can tell that from the app failing.
 *
 * An empty [sink] is a renderer that would not say, and is given the first
 * choice and the benefit of the doubt.
 */
fun pickFor(sink: List<ProtocolInfo>, choices: List<CastItem>): Pick? {
    val first = choices.firstOrNull() ?: return null
    if (sink.isEmpty()) return Pick(first, "http-get:*:${first.contentType}:*")
    for (choice in choices) {
        if (sink.any { it.takes(choice.contentType) }) {
            // Our own type and a wildcard fourth field rather than the line
            // the renderer listed: its DLNA profile names something specific
            // about the stream inside, which this app does not know.
            return Pick(choice, "http-get:*:${choice.contentType}:*")
        }
    }
    return null
}
