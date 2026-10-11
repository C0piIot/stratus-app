package dev.stratus.core.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

/**
 * What iOS says the path costs.
 *
 * `nw_path_is_expensive` is the platform's own word for the same question
 * Android answers with `NET_CAPABILITY_NOT_METERED`: cellular, and a personal
 * hotspot too, which is the case a transport check would miss.
 *
 * A monitor rather than a stored flag because there is no synchronous way to
 * ask -- it reports once immediately on starting, which is the answer, and is
 * cancelled in the same breath. Unverified on a device, like everything else
 * on this side (stratus-app#117).
 */
@OptIn(ExperimentalForeignApi::class)
class IosNetwork : Network {
    override suspend fun metered(): Boolean = suspendCancellableCoroutine { continuation ->
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (continuation.isActive) {
                // No path at all is treated as metered, for the reason Network
                // gives: it costs nothing, because there is nothing to send over.
                val reachable = path != null && nw_path_get_status(path) == nw_path_status_satisfied
                continuation.resume(!reachable || nw_path_is_expensive(path))
            }
            nw_path_monitor_cancel(monitor)
        }
        nw_path_monitor_start(monitor)
        continuation.invokeOnCancellation { nw_path_monitor_cancel(monitor) }
    }
}
