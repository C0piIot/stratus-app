package dev.stratus.core.cast

import android.content.Context
import android.net.wifi.WifiManager
import dev.stratus.core.cast.dlna.DatagramSsdpSocket
import dev.stratus.core.cast.dlna.SsdpSocket

/**
 * SSDP with a `MulticastLock` held for as long as it listens. Without one,
 * plenty of phones filter multicast on wifi to save the battery, and a search
 * goes out and nothing comes back -- which looks exactly like a network with
 * no television on it.
 */
class AndroidSsdpSocket(context: Context) : SsdpSocket {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val datagrams = DatagramSsdpSocket()

    override suspend fun search(request: String, listenMillis: Long, heard: (String) -> Unit) {
        val lock = wifi.createMulticastLock("stratus-dlna").apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            datagrams.search(request, listenMillis, heard)
        } finally {
            lock.release()
        }
    }
}
