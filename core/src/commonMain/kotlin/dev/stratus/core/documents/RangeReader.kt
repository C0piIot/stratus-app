package dev.stratus.core.documents

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray

/**
 * Reads a file somebody else is seeking around in, over HTTP ranges.
 *
 * This exists for one shape of caller: Android hands a picker a file
 * descriptor and the app on the other end reads it like a local file --
 * small reads, mostly one after another, occasionally jumping. Fetching the
 * whole file first would mean a four-gigabyte download to watch thirty
 * seconds of it.
 *
 * **One request serves a run of sequential reads.** The response is held open
 * and each read takes the next bytes off it; only an offset that is not where
 * the last one ended closes it and opens another at the new place. A player
 * streaming a film therefore costs one request, and seeking costs one more
 * each time -- rather than one per four-kilobyte read, which is what a plain
 * range-per-call reader would do and would be worse than useless.
 *
 * What it costs is a connection held open while the reader is idle, which is
 * the right way round: a paused film is one somebody is about to carry on
 * watching. [close] ends it.
 *
 * The handshake is two coroutines because the channel is only valid inside the
 * block that opened it -- so that block parks on the next ask rather than
 * returning.
 */
class RangeReader(
    scope: CoroutineScope,
    private val size: Long,
    /** Opens the file at `from` and calls the block with the bytes from there on. */
    private val open: suspend (from: Long, use: suspend (ByteReadChannel) -> Unit) -> Unit,
) {
    private class Ask(val offset: Long, val length: Int, val reply: CompletableDeferred<ByteArray>)

    private val asks = Channel<Ask>(Channel.UNLIMITED)

    private val server = scope.launch {
        try {
            serve()
        } catch (_: ClosedReceiveChannelException) {
            // close() was called, which is the ordinary end.
        }
    }

    /** The bytes at [offset], up to [length] of them; fewer only at the end of the file. */
    suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= size || length <= 0) return ByteArray(0)
        val wanted = minOf(length.toLong(), size - offset).toInt()
        val reply = CompletableDeferred<ByteArray>()
        asks.send(Ask(offset, wanted, reply))
        return reply.await()
    }

    fun close() {
        asks.close()
        server.cancel()
    }

    private suspend fun serve() {
        var pending: Ask? = null
        while (true) {
            val first = pending ?: asks.receive()
            pending = null
            var seeked: Ask? = null
            try {
                open(first.offset) { channel ->
                    var pos = first.offset
                    var current: Ask? = first
                    while (true) {
                        val ask = current ?: break
                        // Not where the last read ended: leave the block, which
                        // closes the response, and open another from there.
                        if (ask.offset != pos) {
                            seeked = ask
                            break
                        }
                        val bytes = channel.take(ask.length)
                        pos += bytes.size
                        ask.reply.complete(bytes)
                        current = asks.receive()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClosedReceiveChannelException) {
                throw e
            } catch (e: Exception) {
                // Whoever was waiting hears about it, and the next ask opens a
                // request of its own: a reader that gave up for good would turn
                // one dropped connection into a file nothing can read again.
                first.reply.completeExceptionally(e)
            }
            pending = seeked
        }
    }

    /** Up to [length] bytes, which is fewer only where the body ends. */
    private suspend fun ByteReadChannel.take(length: Int): ByteArray {
        var got = ByteArray(0)
        while (got.size < length) {
            val piece = readRemaining((length - got.size).toLong()).readByteArray()
            if (piece.isEmpty()) break
            got = if (got.isEmpty()) piece else got + piece
        }
        return got
    }
}
