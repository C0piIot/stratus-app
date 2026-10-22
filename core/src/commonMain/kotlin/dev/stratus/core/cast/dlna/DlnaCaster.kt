package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastDevice
import dev.stratus.core.cast.CastItem
import dev.stratus.core.cast.CastOutcome
import dev.stratus.core.cast.Caster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Casting to DLNA renderers (stratus-app#153), in common code because almost
 * none of it is the platform's: what goes over the wire is [Ssdp] and
 * [DlnaControl], and all a platform supplies is [SsdpSocket].
 *
 * Unlike a Chromecast, a renderer says what it plays, so [play] chooses among
 * [CastItem.choices] and answers [CastOutcome.CannotPlay] rather than sending
 * a television something it will show as black.
 */
class DlnaCaster(
    private val socket: SsdpSocket,
    private val control: DlnaControl,
    private val scope: CoroutineScope,
) : Caster {
    override val available = true

    private val found = MutableStateFlow<Map<String, Renderer>>(emptyMap())
    private val listed = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices: StateFlow<List<CastDevice>> = listed.asStateFlow()

    private var looking: Job? = null
    private var playing: Renderer? = null

    /**
     * Searches again every few seconds for as long as somebody is choosing:
     * SSDP is datagrams on wifi, and a search one renderer missed is a
     * television that never appears in the list.
     */
    override fun startDiscovery() {
        if (looking?.isActive == true) return
        looking = scope.launch {
            val described = mutableSetOf<String>()
            while (isActive) {
                val seen = mutableListOf<Sighting>()
                try {
                    socket.search(Ssdp.search(), LISTEN_MS) { datagram -> Ssdp.sightingOf(datagram)?.let(seen::add) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // No wifi, or a socket the platform would not open: the
                    // list stays as it is and the next round tries again.
                }
                for (sighting in seen.distinctBy { it.location }) {
                    if (described.add(sighting.location)) describe(sighting)
                }
                delay(AGAIN_MS)
            }
        }
    }

    override fun stopDiscovery() {
        looking?.cancel()
        looking = null
    }

    override suspend fun play(device: CastDevice, item: CastItem): CastOutcome {
        // Gone from the network since it was listed: there is nobody to tell.
        val renderer = found.value[device.id] ?: return CastOutcome.Started
        val pick = pickFor(control.protocolInfo(renderer), item.choices)
            ?: return CastOutcome.CannotPlay(item.choices.map { it.contentType })
        try {
            control.load(renderer, pick)
            playing = renderer
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refused after saying it plays the type: the profile inside, which
            // nothing here knows, is the renderer's to object to. What it shows
            // is its own report, the way a Chromecast's is.
        }
        return CastOutcome.Started
    }

    override suspend fun stop() {
        val renderer = playing ?: return
        playing = null
        try {
            control.stop(renderer)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Already stopped, switched off, or gone: stopped either way.
        }
    }

    private suspend fun describe(sighting: Sighting) {
        val renderer = try {
            control.describe(sighting.location)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val id = DEVICE_PREFIX + renderer.id.ifEmpty { sighting.location }
        found.update { it + (id to renderer) }
        listed.update { list -> list.filterNot { it.id == id } + CastDevice(id, renderer.name) }
    }

    private companion object {
        /** So an id can never be mistaken for a Cast route's. */
        const val DEVICE_PREFIX = "dlna:"
        const val LISTEN_MS = 3_000L
        const val AGAIN_MS = 5_000L
    }
}
