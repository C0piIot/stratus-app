package dev.stratus.app.e2e

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A TCP proxy between the app and the server, inside the test process, that
 * the test can break on cue.
 *
 * The app is signed in to `127.0.0.1:<port>` and this forwards to the backend,
 * so a server that disappears, stops answering or drops a transfer halfway is a
 * method call rather than a container somebody outside has to stop.
 */
class FaultyLink(private val upstream: InetSocketAddress) : Closeable {

    private companion object {
        const val TAG = "FaultyLink"

        // Named, because Android's getLoopbackAddress() is ::1 -- a listener
        // there refuses the 127.0.0.1 that [address] hands out.
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }

    val port: Int
    val address: String get() = "http://127.0.0.1:$port"

    @Volatile private var listener: ServerSocket
    @Volatile private var stalled = false
    private val live = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** Everything the app has sent towards the server, which is how a resume is told from a restart. */
    val sent = AtomicLong(0)

    /** Bytes the app may still send before the connection is cut; negative for no limit. */
    private val uploadBudget = AtomicLong(-1)

    /** Bytes the server may still send before the connection is cut; negative for no limit. */
    private val downloadBudget = AtomicLong(-1)

    init {
        listener = ServerSocket(0, 50, LOOPBACK)
        port = listener.localPort
        serve(listener)
    }

    /** Nothing listens: the app gets a refused connection, as from a server that is off. */
    fun down() {
        listener.close()
        dropAll()
    }

    /** Listening again on the same port, forwarding normally. */
    fun up() {
        stalled = false
        if (!listener.isClosed) return
        listener = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(LOOPBACK, port))
        }
        serve(listener)
    }

    /** Connections are accepted and then nothing is ever answered. */
    fun stall() {
        stalled = true
        dropAll()
    }

    /** The next connection that sends more than [bytes] towards the server is cut there. */
    fun cutUploadAfter(bytes: Long) = uploadBudget.set(bytes)

    /** The next answer longer than [bytes] is cut there. */
    fun cutDownloadAfter(bytes: Long) = downloadBudget.set(bytes)

    override fun close() {
        listener.close()
        dropAll()
    }

    private fun dropAll() {
        synchronized(live) { live.toList() }.forEach { runCatching { it.close() } }
        live.clear()
    }

    private fun serve(on: ServerSocket) = thread(isDaemon = true, name = "faulty-link-accept") {
        while (!on.isClosed) {
            val client = try { on.accept() } catch (_: IOException) { return@thread }
            live += client
            Log.i(TAG, "accepted ${client.remoteSocketAddress} stalled=$stalled")
            if (stalled) continue
            val server = try {
                Socket().apply { connect(upstream, 5_000) }
            } catch (e: IOException) {
                Log.w(TAG, "could not reach $upstream", e)
                client.close()
                continue
            }
            live += server
            pump(client.getInputStream(), server.getOutputStream(), uploadBudget, client, server, sent)
            pump(server.getInputStream(), client.getOutputStream(), downloadBudget, client, server, null)
        }
    }

    private fun pump(from: InputStream, to: OutputStream, budget: AtomicLong, a: Socket, b: Socket, count: AtomicLong?) =
        thread(isDaemon = true, name = "faulty-link-pump") {
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    val left = budget.get()
                    if (left in 0 until n) {
                        // One-shot: the retry that follows is what is under test.
                        budget.set(-1)
                        to.write(buffer, 0, left.toInt())
                        to.flush()
                        break
                    }
                    if (left >= 0) budget.addAndGet(-n.toLong())
                    count?.addAndGet(n.toLong())
                    to.write(buffer, 0, n)
                    to.flush()
                }
            } catch (e: IOException) {
                Log.i(TAG, "pump ended: $e")
            } finally {
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }
}
