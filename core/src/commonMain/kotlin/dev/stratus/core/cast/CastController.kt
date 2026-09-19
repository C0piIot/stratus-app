package dev.stratus.core.cast

import dev.stratus.core.dav.DavResource
import dev.stratus.core.share.LinkSupport
import dev.stratus.core.share.ShareLinks
import io.ktor.util.date.getTimeMillis
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
    data class Playing(val device: CastDevice, val item: CastItem) : CastState
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

    /** Whether there is any point offering this at all for a given file. */
    fun canCast(entry: DavResource): Boolean =
        caster.available && support.offered && castItemFor(entry, links, now()) != null

    /**
     * Asked for. Warns first where a warning is owed, and otherwise goes
     * straight to choosing a screen.
     */
    fun offer(entry: DavResource) {
        val item = castItemFor(entry, links, now()) ?: return
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
        scope.launch { caster.play(device, item) }
    }

    fun stop() {
        caster.stopDiscovery()
        mutable.value = CastState.Idle
        scope.launch { caster.stop() }
    }

    /** Asks the server whether the link means anything before looking for a screen. */
    private suspend fun choose(item: CastItem) {
        if (!support.honours(item.url)) {
            mutable.value = CastState.TheServerDoesNotDoLinks
            return
        }
        caster.startDiscovery()
        mutable.value = CastState.Choosing(item)
    }
}
