package dev.stratus.core.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finding screens and loading a URL on one. It decides nothing else.
 *
 * Built only where Google Play Services answered for itself, so `available` is
 * true by construction -- the phone that has none gets [NoCaster] instead.
 *
 * Deliberately not `MediaRouteButton` and the SDK's own dialogs: that button
 * requires a `Theme.AppCompat` and would drag AppCompat into an app that is
 * Compose throughout, to render a list this app can render itself. What is here
 * is the two things only the SDK can do -- discovery and a session.
 */
class AndroidCaster(private val context: Context) : Caster {

    override val available = true

    private val mutable = MutableStateFlow(emptyList<CastDevice>())
    override val devices = mutable.asStateFlow()

    private val router by lazy { MediaRouter.getInstance(context) }

    private val selector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()
    }

    private val watching = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    override fun startDiscovery() {
        // ACTIVE_SCAN rather than passive: the list is being looked at, and a
        // passive scan can take tens of seconds to find anything.
        router.addCallback(selector, watching, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    override fun stopDiscovery() = router.removeCallback(watching)

    override suspend fun play(device: CastDevice, item: CastItem) = withContext(Dispatchers.Main) {
        val route = router.routes.firstOrNull { it.id == device.id } ?: return@withContext
        router.selectRoute(route)

        val session = awaitSession() ?: return@withContext
        val media = MediaInfo.Builder(item.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(item.contentType)
            .setMetadata(
                MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                    putString(MediaMetadata.KEY_TITLE, item.title)
                },
            )
            .build()

        session.remoteMediaClient?.load(
            MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build(),
        )
        Unit
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        CastContext.getSharedInstance(context).sessionManager.endCurrentSession(true)
    }

    /**
     * The session, whether it was already there or has just been asked for.
     *
     * Selecting a route starts one asynchronously, and there is no version of
     * this that returns immediately. The timeout is what keeps a screen that
     * never answers from leaving somebody waiting on a dialog for ever.
     */
    private suspend fun awaitSession(): CastSession? {
        val manager = CastContext.getSharedInstance(context).sessionManager
        manager.currentCastSession?.let { if (it.isConnected) return it }

        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { waiting ->
                val listener = object : SessionManagerListener<CastSession> {
                    override fun onSessionStarted(session: CastSession, sessionId: String) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        waiting.resume(session)
                    }

                    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        waiting.resume(session)
                    }

                    override fun onSessionStartFailed(session: CastSession, error: Int) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        waiting.resume(null)
                    }

                    override fun onSessionResumeFailed(session: CastSession, error: Int) {
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        waiting.resume(null)
                    }

                    override fun onSessionStarting(session: CastSession) = Unit
                    override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                    override fun onSessionEnding(session: CastSession) = Unit
                    override fun onSessionEnded(session: CastSession, error: Int) = Unit
                    override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                waiting.invokeOnCancellation {
                    manager.removeSessionManagerListener(listener, CastSession::class.java)
                }
            }
        }
    }

    private fun refresh() {
        mutable.value = router.routes
            .filter { it.matchesSelector(selector) && !it.isDefaultOrBluetooth }
            .map { CastDevice(it.id, it.name) }
    }

    private val MediaRouter.RouteInfo.isDefaultOrBluetooth: Boolean
        get() = isDefault || isBluetooth

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
