package dev.stratus.core.dav

/**
 * Percent-encoding for the path part of a WebDAV URL.
 *
 * Encoded one segment at a time rather than over the whole path, because the
 * separator is the only character that has to survive: a file genuinely called
 * `a/b` is one name, not two directories, and encoding the path as a unit
 * cannot tell the difference.
 */
object DavPath {
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val HEX = "0123456789ABCDEF"

    /** Encodes one path segment. A `/` in [segment] is escaped, not preserved. */
    fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            if (value < 0x80 && value.toChar() in UNRESERVED) {
                append(value.toChar())
            } else {
                append('%').append(HEX[value shr 4]).append(HEX[value and 0xF])
            }
        }
    }

    /** Encodes each segment of [path], leaving the separators alone. */
    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    /**
     * Reverses percent-encoding over a whole path, separators included.
     *
     * Runs of ordinary characters are copied as characters rather than
     * re-encoded, so a name outside the basic multilingual plane survives:
     * taking a surrogate apart into bytes and putting it back is how an emoji
     * turns into two replacement characters.
     */
    fun decode(value: String): String {
        if ('%' !in value) return value
        val out = StringBuilder(value.length)
        val bytes = mutableListOf<Byte>()
        fun flush() {
            if (bytes.isNotEmpty()) {
                out.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val high = hexDigit(value[i + 1])
                val low = hexDigit(value[i + 2])
                if (high >= 0 && low >= 0) {
                    bytes.add(((high shl 4) or low).toByte())
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
