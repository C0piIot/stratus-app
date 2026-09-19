package dev.stratus.core.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A screen on the network. */
data class CastDevice(val id: String, val name: String)

/**
 * The platform's Cast sender, and a dumb executor like every other platform half
 * here: it finds screens, connects to one and loads a URL. What to load, whether
 * to warn first and what to say when there is nothing to play are decided next
 * door, where a test can reach them.
 *
 * [available] is false on a phone with no Google Play Services -- the Cast SDK
 * lives inside them -- and on iOS, which has no sender yet. The app is expected
 * to be whole without it: no button, no menu entry, nothing broken.
 */
interface Caster {
    val available: Boolean
    val devices: StateFlow<List<CastDevice>>

    fun startDiscovery()
    fun stopDiscovery()

    suspend fun play(device: CastDevice, item: CastItem)
    suspend fun stop()
}

/** What a phone without Play Services gets, and iOS until it has a sender. */
class NoCaster : Caster {
    override val available = false
    override val devices = MutableStateFlow(emptyList<CastDevice>()).asStateFlow()
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override suspend fun play(device: CastDevice, item: CastItem) = Unit
    override suspend fun stop() = Unit
}
