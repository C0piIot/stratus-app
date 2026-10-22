package dev.stratus.core.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Part(override val available: Boolean, vararg screens: CastDevice) : Caster {
    override val devices = MutableStateFlow(screens.toList())
    var looking = false
    var played: CastDevice? = null
    override fun startDiscovery() { looking = true }
    override fun stopDiscovery() { looking = false }
    override suspend fun play(device: CastDevice, item: CastItem): CastOutcome {
        played = device
        return CastOutcome.Started
    }
    override suspend fun stop() { played = null }
}

class CastersTest {
    private val item = CastItem("https://s/a.mp4?k=t", "video/mp4", "a.mp4")
    private val chromecast = CastDevice("route-1", "Kitchen")
    private val renderer = CastDevice("dlna:uuid:tv", "Living room")

    @Test
    fun everyScreenIsOneListAndEachIsPlayedByItsOwnSender() = runTest {
        val cast = Part(true, chromecast)
        val dlna = Part(true, renderer)
        val both = Casters(listOf(cast, dlna), backgroundScope)

        assertEquals(listOf(chromecast, renderer), both.devices.first { it.size == 2 })
        both.startDiscovery()
        assertTrue(cast.looking && dlna.looking)

        both.play(renderer, item)
        assertEquals(renderer, dlna.played)
        assertNull(cast.played)
        both.stop()
        assertNull(dlna.played)
    }

    @Test
    fun aSenderThisPhoneDoesNotHaveIsLeftOut() = runTest {
        // No Play Services: the renderers are still there.
        val cast = Part(false, chromecast)
        val dlna = Part(true, renderer)
        val both = Casters(listOf(cast, dlna), backgroundScope)
        assertTrue(both.available)
        assertEquals(listOf(renderer), both.devices.first { it.isNotEmpty() })
        both.startDiscovery()
        assertFalse(cast.looking)

        assertFalse(Casters(listOf(cast), backgroundScope).available)
    }
}
