package dev.stratus.core.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.stratus.core.cast.relay.Relay
import dev.stratus.core.cast.relay.SocketRelay
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import java.net.Inet4Address

/**
 * Holds the process up while a renderer reads through the relay: the film is
 * coming from this phone, and Android stops a process nothing visible needs.
 * It does nothing else -- the relay serves, this only keeps it alive and says
 * so in the shade, which is what a foreground service is for.
 */
class CastRelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Playing on a screen")
            .setContentText("The screen is reading it through this phone.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "casting"
        const val NOTIFICATION = 2
    }
}

/**
 * [SocketRelay] on the phone's wifi address, with [CastRelayService] up for
 * as long as something is published.
 */
class AndroidRelay(private val context: Context, http: HttpClient, scope: CoroutineScope) : Relay {
    private val relay = SocketRelay(http, scope) { wifiAddress(context) }

    override suspend fun publish(upstream: String, renderer: String): String? {
        val local = relay.publish(upstream, renderer) ?: return null
        context.startForegroundService(Intent(context, CastRelayService::class.java))
        return local
    }

    override suspend fun withdraw() {
        relay.withdraw()
        context.stopService(Intent(context, CastRelayService::class.java))
    }
}

/**
 * The phone's IPv4 address on the network it is on, which is where a renderer
 * can reach it -- and the only interface the relay listens on. Null with no
 * network, or on one with no private address, where a renderer could not be.
 */
private fun wifiAddress(context: Context): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val links = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
    return links.linkAddresses
        .map { it.address }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}
