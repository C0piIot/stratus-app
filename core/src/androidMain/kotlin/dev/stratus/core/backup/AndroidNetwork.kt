package dev.stratus.core.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What Android says the active connection costs.
 *
 * `NET_CAPABILITY_NOT_METERED` and not the network's transport: a phone
 * tethered to another phone is on wifi and is somebody's data plan all the
 * same, and a home connection with a cap can be declared metered by its owner.
 * The capability is the question actually being asked.
 */
class AndroidNetwork(private val context: Context) : Network {
    override suspend fun metered(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return true
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
