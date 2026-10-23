package dev.stratus.core.cast

import dev.stratus.core.cast.dlna.Ssdp
import dev.stratus.core.cast.dlna.SsdpSocket
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.errno
import platform.posix.inet_addr
import platform.posix.recv
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * SSDP over a BSD socket, the same three calls the JVM half makes through
 * `java.net` (stratus-app#153).
 *
 * POSIX rather than Network.framework because this is one datagram out and
 * a few back: `NWConnectionGroup` would be a callback API bridged into a
 * coroutine to do what `sendto` and `recv` do in a loop. **What it cannot do
 * without Apple's say-so is reach the group**: since iOS 14 a datagram to a
 * multicast address needs `com.apple.developer.networking.multicast`, which is
 * granted on request and waits on stratus-app#5. Without it the send fails,
 * [DlnaCaster][dev.stratus.core.cast.dlna.DlnaCaster] shrugs, and the list
 * stays empty. [host] and [port] exist for the test, which answers on the
 * loopback where no entitlement is asked for.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSsdpSocket(
    private val host: String = Ssdp.HOST,
    private val port: Int = Ssdp.PORT,
) : SsdpSocket {

    override suspend fun search(request: String, listenMillis: Long, heard: (String) -> Unit) = withContext(Dispatchers.IO) {
        val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        check(fd >= 0) { "socket: errno $errno" }
        try {
            memScoped {
                val to = alloc<sockaddr_in>()
                to.sin_len = sizeOf<sockaddr_in>().convert()
                to.sin_family = AF_INET.convert()
                to.sin_port = networkOrder(port)
                to.sin_addr.s_addr = inet_addr(host)
                val bytes = request.encodeToByteArray()
                val sent = bytes.usePinned {
                    sendto(fd, it.addressOf(0), bytes.size.convert(), 0, to.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
                }
                check(sent >= 0) { "sendto $host:$port: errno $errno" }

                val buffer = ByteArray(DATAGRAM)
                val wait = alloc<timeval>()
                val deadline = TimeSource.Monotonic.markNow() + listenMillis.milliseconds
                while (true) {
                    val left = -deadline.elapsedNow()
                    if (!left.isPositive()) break
                    // The timeout is per call, so it is set to what is left
                    // each time round rather than once for the whole wait.
                    wait.tv_sec = left.inWholeSeconds.convert()
                    wait.tv_usec = (left.inWholeMicroseconds % 1_000_000).convert()
                    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())
                    val got = buffer.usePinned { recv(fd, it.addressOf(0), buffer.size.convert(), 0) }
                    // A timeout and a failure both end the wait: either way
                    // there is nothing more to hear on this search.
                    if (got <= 0) break
                    heard(buffer.decodeToString(0, got.toInt()))
                }
            }
        } finally {
            close(fd)
        }
    }

    private companion object {
        /** Room for a verbose answer, as on the JVM. */
        const val DATAGRAM = 8192

        /** `htons`, which Darwin has only as a macro. Every iOS device is little-endian. */
        fun networkOrder(port: Int): UShort = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()
    }
}
