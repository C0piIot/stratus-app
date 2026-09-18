package dev.stratus.core.net

import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.io.RawSource
import kotlinx.io.buffered

/**
 * A request body that streams from a source.
 *
 * Shared by the two ways this app writes a file -- a WebDAV `PUT` and a tus
 * `PATCH` -- because the files it exists for are videos, and holding one in
 * memory to send it is the difference between working and being killed for it.
 */
internal fun sourceBody(size: Long, source: RawSource): OutgoingContent =
    object : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long = size

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buffered = source.buffered()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = buffered.readAtMostTo(chunk, 0, chunk.size)
                if (read <= 0) break
                channel.writeFully(chunk, 0, read)
            }
            channel.flush()
        }
    }

private const val CHUNK_BYTES = 64 * 1024
