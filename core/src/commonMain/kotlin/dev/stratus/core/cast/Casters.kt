package dev.stratus.core.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Several senders as one: a Chromecast and a DLNA renderer are both a screen
 * on the network to whoever is choosing, and [CastController] needs to know
 * nothing about which kind it picked.
 *
 * Only the available ones take part, so a phone with no Play Services still
 * lists the renderers -- which is most of what DLNA is worth on such a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Casters(parts: List<Caster>, scope: CoroutineScope) : Caster {
    private val parts = parts.filter { it.available }
    override val available = this.parts.isNotEmpty()

    override val devices: StateFlow<List<CastDevice>> =
        (if (this.parts.isEmpty()) flowOf(emptyList()) else combine(this.parts.map { it.devices }) { it.toList().flatten() })
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val playing = MutableStateFlow<Caster?>(null)

    /** The sender that is playing says where it has got to, and nobody else does. */
    override val playback: StateFlow<Playback?> =
        playing.flatMapLatest { it?.playback ?: flowOf(null) }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun startDiscovery() = parts.forEach { it.startDiscovery() }
    override fun stopDiscovery() = parts.forEach { it.stopDiscovery() }

    override suspend fun play(device: CastDevice, item: CastItem): CastOutcome {
        val owner = parts.firstOrNull { part -> part.devices.value.any { it.id == device.id } }
            ?: return CastOutcome.Failed("${device.name} is no longer on the network")
        playing.value = owner
        return owner.play(device, item)
    }

    override suspend fun icon(device: CastDevice): ByteArray? =
        parts.firstOrNull { part -> part.devices.value.any { it.id == device.id } }?.icon(device)

    override suspend fun stop() {
        playing.value?.stop()
        playing.value = null
    }

    override suspend fun pause() { playing.value?.pause() }
    override suspend fun resume() { playing.value?.resume() }
    override suspend fun seek(seconds: Long) { playing.value?.seek(seconds) }
    override suspend fun volume(percent: Int) { playing.value?.volume(percent) }

    override fun begin() = parts.forEach { it.begin() }
    override fun note(message: String) = parts.forEach { it.note(message) }
    override fun report(outcome: String) = parts.forEach { it.report(outcome) }
}
