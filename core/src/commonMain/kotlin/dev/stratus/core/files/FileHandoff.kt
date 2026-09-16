package dev.stratus.core.files

import kotlinx.io.Sink

/**
 * Where a file goes once it leaves the app.
 *
 * The split is the usual one: this decides *where*, and the caller decides
 * *what*. It takes a function that writes the bytes rather than the bytes
 * themselves, which is the whole point -- somebody's four-gigabyte video has to
 * stream from the server into the destination without ever being a `ByteArray`
 * in the middle.
 */
interface FileHandoff {
    /** Hands the file to whatever the system uses to view it. */
    suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit)

    /** Keeps a copy somewhere the person can find it again. */
    suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit)
}
