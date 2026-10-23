package dev.stratus.core.cast

import dev.stratus.core.dav.DavResource
import dev.stratus.core.share.LinkAnswer
import dev.stratus.core.share.LinkSupport
import dev.stratus.core.share.ShareLinks
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where casting has got to. */
sealed interface CastState {
    data object Idle : CastState

    /**
     * The server's certificate is trusted here and nowhere else.
     *
     * A television has nobody to ask about a certificate, so it will simply
     * fetch nothing. This is the one failure worth predicting, because it is
     * invisible at the other end: the film just never starts.
     */
    data class CertificateIsOnlyTrustedHere(val item: CastItem) : CastState

    data class Choosing(val item: CastItem) : CastState

    /**
     * The link was signed and this server did not know what it was.
     *
     * Signed links are Stratus's, and this app works against any WebDAV server
     * -- so a television is the wrong thing to blame, and nobody should be left
     * watching one that will never start.
     */
    data object TheServerDoesNotDoLinks : CastState

    /** Asked whether the link works and got no answer, which is not a no. */
    data object TheServerDidNotAnswer : CastState
    data class Playing(val device: CastDevice, val item: CastItem) : CastState

    /**
     * The screen said it plays none of [offered], so nothing was sent to it.
     * Said rather than tried, because what a screen does with what it cannot
     * play is show nothing at all.
     */
    data class ScreenCannotPlay(val device: CastDevice, val item: CastItem, val offered: List<String>) : CastState

    /** The screen refused, or stopped answering, with what it said. */
    data class ScreenFailed(val device: CastDevice, val item: CastItem, val reason: String) : CastState
}

/**
 * Casting, decided here and performed by [Caster].
 *
 * Everything that could be got wrong is in this class: which URL a television is
 * given, whether it is worth warning somebody first, and what happens when they
 * say go on anyway.
 */
class CastController(
    private val caster: Caster,
    private val links: ShareLinks,
    /**
     * Whether this server is reached over https with a certificate only this
     * device vouches for -- a pin from stratus-app#31.
     */
    private val certificateIsPinned: Boolean,
    private val support: LinkSupport,
    private val scope: CoroutineScope,
    private val now: () -> Long = { getTimeMillis() / 1000 },
) {
    private val mutable = MutableStateFlow<CastState>(CastState.Idle)
    val state: StateFlow<CastState> = mutable.asStateFlow()

    val available: Boolean get() = caster.available
    val devices: StateFlow<List<CastDevice>> get() = caster.devices

    /** A screen's picture of itself, for the list somebody chooses from. */
    suspend fun iconOf(device: CastDevice): ByteArray? = caster.icon(device)

    /** Where the screen has got to, for as long as something is playing. */
    val playback: StateFlow<Playback?> get() = caster.playback

    /** Whether there is any point offering this at all for a given file. */
    fun canCast(entry: DavResource): Boolean =
        caster.available && support.offered && castItemFor(entry, links, now()) != null

    /**
     * Asked for. Warns first where a warning is owed, and otherwise goes
     * straight to choosing a screen.
     */
    fun offer(entry: DavResource) {
        val item = castItemFor(entry, links, now()) ?: return
        caster.begin()
        caster.note("offered ${entry.path} (${entry.contentType}) as ${item.contentType} ${item.url}")
        item.fallback?.let { caster.note("fallback ${it.contentType} ${it.url}") }
        if (certificateIsPinned) {
            // Before the link is even tried: what the warning is about is the
            // television's inability to check a certificate, and asking the
            // server anything first would only delay saying so.
            mutable.value = CastState.CertificateIsOnlyTrustedHere(item)
            return
        }
        scope.launch { choose(item) }
    }

    /**
     * Warned and asked to go on anyway.
     *
     * Offered rather than refused because the app cannot be sure: a pin is only
     * consulted when the system's own validation fails, so a server that has
     * since been given a real certificate would be warned about for nothing.
     */
    fun goOnAnyway() {
        val item = (mutable.value as? CastState.CertificateIsOnlyTrustedHere)?.item ?: return
        scope.launch { choose(item) }
    }

    fun playOn(device: CastDevice) {
        val item = (mutable.value as? CastState.Choosing)?.item ?: return
        caster.stopDiscovery()
        mutable.value = CastState.Playing(device, item)
        scope.launch {
            // A screen is somebody else's software on somebody else's network,
            // and nothing it does may take the app down with it: one that
            // started answering and never finished was a crash (STRATUS-APP-6).
            val outcome = try {
                caster.play(device, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CastOutcome.Failed(e.message ?: e::class.simpleName ?: "no answer")
            }
            // Unless somebody stopped it meanwhile, which the state already says.
            if (mutable.value != CastState.Playing(device, item)) return@launch
            when (outcome) {
                is CastOutcome.CannotPlay -> {
                    caster.note("${device.name} plays none of ${outcome.offered}")
                    caster.report("screen cannot play it")
                    mutable.value = CastState.ScreenCannotPlay(device, item, outcome.offered)
                }
                is CastOutcome.Failed -> {
                    caster.note("${device.name} failed: ${outcome.reason}")
                    caster.report("screen failed")
                    mutable.value = CastState.ScreenFailed(device, item, outcome.reason)
                }
                CastOutcome.Started -> Unit
            }
        }
    }

    fun pause() { if (playing()) scope.launch { caster.pause() } }
    fun resume() { if (playing()) scope.launch { caster.resume() } }
    fun seekTo(seconds: Long) { if (playing()) scope.launch { caster.seek(seconds.coerceAtLeast(0)) } }
    fun setVolume(percent: Int) { if (playing()) scope.launch { caster.volume(percent.coerceIn(0, 100)) } }

    /** A control pressed while nothing plays -- a stale button -- does nothing. */
    private fun playing() = mutable.value is CastState.Playing

    fun stop() {
        caster.stopDiscovery()
        mutable.value = CastState.Idle
        scope.launch { caster.stop() }
    }

    /**
     * Asks the server whether the link means anything before looking for a
     * screen, and takes the fallback where the first choice is not served.
     */
    private suspend fun choose(offered: CastItem) {
        val fallback = offered.fallback
        val item = if (fallback != null && !support.serves(offered.url, offered.contentType)) {
            caster.note("not served as ${offered.contentType} (${support.lastAnswer}), falling back to ${fallback.contentType}")
            fallback
        } else {
            offered
        }
        when (support.honours(item.url)) {
            LinkAnswer.Honoured -> Unit
            LinkAnswer.Refused -> {
                caster.note("link refused (${support.lastAnswer}): ${item.url}")
                caster.report("link refused")
                mutable.value = CastState.TheServerDoesNotDoLinks
                return
            }
            LinkAnswer.NoAnswer -> {
                caster.note("no answer about the link (${support.lastAnswer}): ${item.url}")
                caster.report("no answer about the link")
                mutable.value = CastState.TheServerDidNotAnswer
                return
            }
        }
        caster.note("link answers (${support.lastAnswer}), looking for screens")
        caster.startDiscovery()
        mutable.value = CastState.Choosing(item)
    }
}
