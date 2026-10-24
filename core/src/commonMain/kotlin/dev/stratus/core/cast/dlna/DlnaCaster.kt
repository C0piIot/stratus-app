package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastDevice
import dev.stratus.core.cast.CastItem
import dev.stratus.core.cast.CastOutcome
import dev.stratus.core.cast.Caster
import dev.stratus.core.cast.Playback
import dev.stratus.core.cast.relay.Relay
import io.ktor.http.Url
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
    /**
     * What serves an `https` link over plain http on the local network, for
     * renderers too old to open one; null where there is none, and then every
     * link goes to the renderer as it is.
     */
    private val relay: Relay? = null,
) : Caster {
    override val available = true

    private val found = MutableStateFlow<Map<String, Renderer>>(emptyMap())
    private val listed = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices: StateFlow<List<CastDevice>> = listed.asStateFlow()

    private var looking: Job? = null
    private var playing: Renderer? = null
    private var watching: Job? = null

    private val where = MutableStateFlow<Playback?>(null)
    override val playback: StateFlow<Playback?> = where.asStateFlow()

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
        val renderer = found.value[device.id] ?: return CastOutcome.Failed("${device.name} is no longer on the network")
        val sink = control.protocolInfo(renderer)
        val chosen = pickFor(sink, item.choices)
            ?: return CastOutcome.CannotPlay(
                item.choices.map { it.contentType },
                sink.map { "${it.protocol} ${it.contentType}" }.distinct(),
            )
        val pick = throughRelay(chosen, renderer)
        return try {
            control.load(renderer, pick)
            playing = renderer
            watch(renderer)
            CastOutcome.Started
        } catch (e: CancellationException) {
            throw e
        } catch (e: SoapFault) {
            // Refused after saying it plays the type: the profile inside, which
            // nothing here knows, is the renderer's to object to -- and its
            // words, with UPnP's code, are what somebody needs to see.
            CastOutcome.Failed(listOfNotNull(e.code?.let { "error $it" }, e.message).joinToString(": "))
        } catch (e: Exception) {
            CastOutcome.Failed("no answer from ${renderer.name}: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Fetched once per address and kept for the life of the process: the
     * list is drawn again every time somebody casts, and a television does
     * not change its picture of itself in between. A failure is kept too,
     * as an empty array, so a renderer with a broken icon is not asked again
     * on every redraw.
     */
    private val icons = MutableStateFlow<Map<String, ByteArray>>(emptyMap())

    override suspend fun icon(device: CastDevice): ByteArray? {
        val url = device.icon ?: return null
        val bytes = icons.value[url] ?: (control.bytes(url) ?: ByteArray(0)).also { got -> icons.update { it + (url to got) } }
        return bytes.takeIf { it.isNotEmpty() }
    }

    /**
     * The link as the renderer can open it. Over the relay when it is `https`,
     * because a renderer cannot be asked whether it speaks TLS -- the one that
     * did not (eHomeMediaCenter on Android 4) accepted the link, said it was
     * playing and never fetched a byte. Plain http on the local network is the
     * one thing every renderer fetches, and an `http` link already is that.
     */
    private suspend fun throughRelay(pick: Pick, renderer: Renderer): Pick {
        val relay = relay ?: return pick
        if (!pick.item.url.startsWith("https://")) return pick
        val local = quietly { relay.publish(pick.item.url, Url(renderer.avTransport.control).host) } ?: return pick
        return pick.copy(item = pick.item.copy(url = local))
    }

    override suspend fun stop() {
        quietly { relay?.withdraw() }
        watching?.cancel()
        watching = null
        where.value = null
        val renderer = playing ?: return
        playing = null
        quietly { control.stop(renderer) }
    }

    override suspend fun pause() { playing?.let { quietly { control.pause(it) } } }
    override suspend fun resume() { playing?.let { quietly { control.play(it) } } }

    override suspend fun seek(seconds: Long) {
        val renderer = playing ?: return
        quietly { control.seek(renderer, seconds) }
        // Said at once rather than a second later, so the bar does not jump back.
        where.value = where.value?.copy(positionSeconds = seconds)
    }

    override suspend fun volume(percent: Int) {
        val renderer = playing ?: return
        if (quietly { control.volume(renderer, percent) } == true) where.value = where.value?.copy(volume = percent)
    }

    /**
     * Asks the renderer where it is every second while it plays. UPnP has
     * eventing for this, but it means a listening HTTP server on the phone
     * that the renderer calls back, which is a firewall's business and a
     * second socket to keep alive; a poll costs two small requests a second
     * on the local network and nothing else.
     */
    private fun watch(renderer: Renderer) {
        watching?.cancel()
        watching = scope.launch {
            val volume = quietly { control.currentVolume(renderer) }
            while (isActive) {
                val (position, duration) = quietly { control.position(renderer) } ?: (null to null)
                val state = quietly { control.transportState(renderer) }
                if (position != null) {
                    where.value = Playback(
                        positionSeconds = position,
                        durationSeconds = duration,
                        paused = state == "PAUSED_PLAYBACK",
                        volume = where.value?.volume ?: volume,
                    )
                }
                delay(POLL_MS)
            }
        }
    }

    /** A renderer refusing or gone is a control that did nothing, not a crash. */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
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
        listed.update { list -> list.filterNot { it.id == id } + CastDevice(id, renderer.name, renderer.icon) }
    }

    private companion object {
        /** So an id can never be mistaken for a Cast route's. */
        const val DEVICE_PREFIX = "dlna:"
        const val LISTEN_MS = 3_000L
        const val AGAIN_MS = 5_000L
        const val POLL_MS = 1_000L
    }
}
