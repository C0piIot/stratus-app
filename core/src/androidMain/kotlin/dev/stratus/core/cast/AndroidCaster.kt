package dev.stratus.core.cast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    // Touched from the main thread and from the controller's coroutines alike.
    private val trail = mutableListOf<String>()
    private var reported = false
    private var loaded = false
    private var attempt = 0

    override fun begin() {
        synchronized(trail) {
            trail.clear()
            reported = false
            loaded = false
            attempt++
        }
    }

    // Whole URLs, token and all, so a failed one can be fetched by hand. They
    // go only to crash reports somebody switched on, and the token lives a day.
    override fun note(message: String) {
        Log.i(TAG, message)
        synchronized(trail) {
            if (trail.lastOrNull()?.substringAfter(' ') != message) trail += "+${elapsed()}ms $message"
        }
    }

    /**
     * One event per attempt, at the first outcome: grouped by outcome, so a
     * television that never answers and one that refuses the URL are two issues.
     */
    override fun report(outcome: String) {
        val lines = synchronized(trail) {
            if (reported) return
            reported = true
            trail.toList()
        }
        Log.i(TAG, "outcome: $outcome")
        Sentry.captureMessage("Cast: $outcome", SentryLevel.INFO) { scope ->
            scope.fingerprint = listOf("cast", outcome)
            scope.setTag("cast.outcome", outcome)
            scope.setExtra("trail", lines.joinToString("\n"))
        }
    }

    private var started = 0L
    private fun elapsed(): Long {
        val now = SystemClock.elapsedRealtime()
        if (trail.isEmpty()) started = now
        return now - started
    }

    /** What the receiver says after the load was accepted, which is where a fetch fails. */
    private val receiver = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = client?.mediaStatus ?: return
            val idle = if (status.playerState == MediaStatus.PLAYER_STATE_IDLE) " idle=${idleReason(status.idleReason)}" else ""
            note("receiver: ${playerState(status.playerState)}$idle, media ${status.mediaInfo?.contentId}")
            when {
                status.playerState == MediaStatus.PLAYER_STATE_PLAYING -> report("playing")
                status.playerState == MediaStatus.PLAYER_STATE_PAUSED -> report("playing")
                status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR ->
                    report("receiver error")
            }
        }

        override fun onMediaError(error: MediaError) {
            note("receiver error: type=${error.type} reason=${error.reason} detailed=${error.detailedErrorCode} data=${error.customData}")
            report("receiver error")
        }
    }

    private var client: RemoteMediaClient? = null

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
        note("discovery started, ${router.routes.size} routes known")
    }

    override fun stopDiscovery() = router.removeCallback(watching)

    override suspend fun play(device: CastDevice, item: CastItem) = withContext(Dispatchers.Main) {
        val route = router.routes.firstOrNull { it.id == device.id }
        if (route == null) {
            note("route ${device.name} is gone")
            report("route gone")
            return@withContext
        }
        note("selecting ${device.name}")
        router.selectRoute(route)

        val session = awaitSession()
        if (session == null) {
            report("no session")
            return@withContext
        }
        val screen = session.castDevice
        note(
            "session with ${screen?.friendlyName} (${screen?.modelName}, version ${screen?.deviceVersion}), " +
                "receiver ${session.applicationMetadata?.applicationId} " +
                "${session.applicationMetadata?.name}, status ${session.applicationStatus}",
        )
        val media = MediaInfo.Builder(item.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(item.contentType)
            .apply {
                // Stratus cuts MPEG-TS, and the receiver assumes packed audio
                // unless told otherwise.
                if (item.contentType == HLS_TYPE) {
                    setHlsSegmentFormat(HlsSegmentFormat.TS)
                    setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
                }
            }
            .setMetadata(
                MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
                    putString(MediaMetadata.KEY_TITLE, item.title)
                },
            )
            .build()

        val remote = session.remoteMediaClient
        if (remote == null) {
            note("session has no media client")
            report("no media client")
            return@withContext
        }
        client?.unregisterCallback(receiver)
        client = remote.also { it.registerCallback(receiver) }
        note("loading ${item.contentType} ${item.url}")
        val mine = synchronized(trail) { loaded = true; attempt }
        remote.load(MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true).build())
            .setResultCallback { result ->
                val code = result.status.statusCode
                note(
                    "load answered ${CastStatusCodes.getStatusCodeString(code)} ($code) " +
                        "${result.status.statusMessage.orEmpty()} ${result.customData ?: ""}",
                )
                if (!result.status.isSuccess) report("load refused")
            }
        // A receiver that accepts the load and then fetches nothing says nothing
        // either; whatever it has said by now is the answer.
        delay(ANSWER_WAIT_MS)
        if (synchronized(trail) { attempt == mine }) report("no answer from the receiver")
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        note("stopped")
        if (synchronized(trail) { loaded }) report("stopped before an answer")
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

        val session = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
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
                        note("session failed to start: ${CastStatusCodes.getStatusCodeString(error)} ($error)")
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        waiting.resume(null)
                    }

                    override fun onSessionResumeFailed(session: CastSession, error: Int) {
                        note("session failed to resume: ${CastStatusCodes.getStatusCodeString(error)} ($error)")
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        waiting.resume(null)
                    }

                    override fun onSessionStarting(session: CastSession) = note("session starting")
                    override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
                    override fun onSessionEnding(session: CastSession) = Unit
                    override fun onSessionEnded(session: CastSession, error: Int) =
                        note("session ended: ${CastStatusCodes.getStatusCodeString(error)} ($error)")
                    override fun onSessionSuspended(session: CastSession, reason: Int) = note("session suspended: $reason")
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                waiting.invokeOnCancellation {
                    manager.removeSessionManagerListener(listener, CastSession::class.java)
                }
            }
        }
        if (session == null) note("no session within ${CONNECT_TIMEOUT_MS / 1000} s")
        return session
    }

    private fun refresh() {
        note("routes: " + router.routes.joinToString { "${it.name} cast=${it.matchesSelector(selector)} default=${it.isDefault} bt=${it.isBluetooth}" })
        mutable.value = router.routes
            .filter { it.matchesSelector(selector) && !it.isDefaultOrBluetooth }
            .map { CastDevice(it.id, it.name) }
    }

    private val MediaRouter.RouteInfo.isDefaultOrBluetooth: Boolean
        get() = isDefault || isBluetooth

    private fun playerState(state: Int) = when (state) {
        MediaStatus.PLAYER_STATE_IDLE -> "idle"
        MediaStatus.PLAYER_STATE_BUFFERING -> "buffering"
        MediaStatus.PLAYER_STATE_LOADING -> "loading"
        MediaStatus.PLAYER_STATE_PLAYING -> "playing"
        MediaStatus.PLAYER_STATE_PAUSED -> "paused"
        else -> "state $state"
    }

    private fun idleReason(reason: Int) = when (reason) {
        MediaStatus.IDLE_REASON_ERROR -> "error"
        MediaStatus.IDLE_REASON_FINISHED -> "finished"
        MediaStatus.IDLE_REASON_CANCELED -> "cancelled"
        MediaStatus.IDLE_REASON_INTERRUPTED -> "interrupted"
        MediaStatus.IDLE_REASON_NONE -> "none"
        else -> "reason $reason"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val TAG = "StratusCast"
        const val ANSWER_WAIT_MS = 30_000L
    }
}
