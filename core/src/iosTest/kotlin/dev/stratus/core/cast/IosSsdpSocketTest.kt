package dev.stratus.core.cast

import dev.stratus.core.cast.dlna.Ssdp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import platform.posix.AF_INET
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The socket half on the loopback, where no entitlement is asked for: what is
 * proved is the sending, the hearing and the giving up, which are the parts a
 * typo in a cinterop call would break. Reaching the real group needs Apple's
 * multicast entitlement and a device, and is a case in stratus-app#117.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSsdpSocketTest {

    private val answer = "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:1/d.xml\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    @Test
    fun theSearchIsSentAndTheAnswerIsHeard() = runBlocking {
        val (fd, port) = responder()
        try {
            val asked = async(Dispatchers.IO) { answerOnce(fd) }
            val heard = mutableListOf<String>()
            IosSsdpSocket("127.0.0.1", port).search(Ssdp.search(), 3_000) { heard += it }

            assertTrue(asked.await().startsWith("M-SEARCH * HTTP/1.1"), "the responder heard something else")
            assertEquals(listOf(answer), heard)
            assertEquals("http://127.0.0.1:1/d.xml", Ssdp.sightingOf(heard.single())?.location)
        } finally {
            close(fd)
        }
    }

    @Test
    fun nobodyAnsweringEndsWhenTheTimeIsUp() = runBlocking {
        // A port bound and never read: the search goes out and nothing comes back.
        val (fd, port) = responder()
        try {
            val heard = mutableListOf<String>()
            IosSsdpSocket("127.0.0.1", port).search(Ssdp.search(), 300) { heard += it }
            assertEquals(emptyList(), heard)
        } finally {
            close(fd)
        }
    }

    @Test
    fun anAddressIsInNetworkOrder() {
        // 239.255.255.250 laid out in memory as 239, 255, 255, 250.
        assertEquals(0xFAFFFFEFu, ipv4("239.255.255.250"))
        assertEquals(0x0100007Fu, ipv4("127.0.0.1"))
    }

    /** A UDP socket on the loopback, and the port it was given. */
    private fun responder(): Pair<Int, Int> = memScoped {
        val fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        val at = alloc<sockaddr_in>()
        at.sin_len = sizeOf<sockaddr_in>().convert()
        at.sin_family = AF_INET.convert()
        at.sin_port = 0u
        at.sin_addr.s_addr = ipv4("127.0.0.1")
        check(bind(fd, at.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind" }
        val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        check(getsockname(fd, at.ptr.reinterpret(), length.ptr) == 0) { "getsockname" }
        val port = at.sin_port.toInt()
        fd to (((port and 0xff) shl 8) or ((port shr 8) and 0xff))
    }

    /** Reads one datagram and answers whoever sent it, which is what a renderer does. */
    private fun answerOnce(fd: Int): String = memScoped {
        val wait = alloc<timeval>().apply { tv_sec = 5.convert(); tv_usec = 0 }
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, wait.ptr, sizeOf<timeval>().convert())
        val from = alloc<sockaddr_in>()
        val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
        val buffer = ByteArray(8192)
        val got = buffer.usePinned { recvfrom(fd, it.addressOf(0), buffer.size.convert(), 0, from.ptr.reinterpret(), length.ptr) }
        check(got > 0) { "nothing was sent to the responder" }
        val reply = answer.encodeToByteArray()
        reply.usePinned { sendto(fd, it.addressOf(0), reply.size.convert(), 0, from.ptr.reinterpret(), length.value) }
        buffer.decodeToString(0, got.toInt())
    }
}
