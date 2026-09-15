package dev.stratus.core

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
}
