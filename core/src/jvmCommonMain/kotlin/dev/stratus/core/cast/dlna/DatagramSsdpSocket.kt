package dev.stratus.core.cast.dlna

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * SSDP over a plain datagram socket. Plain JDK, so the JVM conformance suite
 * runs the same code a phone does; what Android adds is only the lock that
 * keeps its wifi from dropping multicast while this listens.
 */
class DatagramSsdpSocket : SsdpSocket {
    override suspend fun search(request: String, listenMillis: Long, heard: (String) -> Unit) = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            val bytes = request.encodeToByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(Ssdp.HOST), Ssdp.PORT))
            val deadline = System.currentTimeMillis() + listenMillis
            val buffer = ByteArray(DATAGRAM)
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                heard(packet.data.decodeToString(0, packet.length))
            }
        }
    }

    private companion object {
        /** An SSDP answer is a few hundred bytes; this is room for a verbose one. */
        const val DATAGRAM = 8192
    }
}
