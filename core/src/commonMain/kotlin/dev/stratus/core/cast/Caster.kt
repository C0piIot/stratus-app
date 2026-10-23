package dev.stratus.core.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A screen on the network. */
data class CastDevice(val id: String, val name: String)

/**
 * Where a screen has got to with what it was given, as it last said. A null
 * duration is a screen that does not know or will not say, and a null volume
 * one whose volume this app cannot set.
 */
data class Playback(
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val paused: Boolean,
    val volume: Int?,
)

/** A position or a length as a player shows one: `3:07`, or `1:02:05` past an hour. */
fun clockOf(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val minutes = (s / 60) % 60
    val rest = (s % 60).toString().padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
}

/** What came of handing a screen something to play. */
sealed interface CastOutcome {
    /**
     * Handed over. Not "playing": a Chromecast that accepts a load and then
     * fetches nothing says nothing either, and the trail is where that shows.
     */
    data object Started : CastOutcome

    /**
     * The screen said beforehand that it plays none of [offered], so nothing
     * was sent. A DLNA renderer can say so and a Chromecast cannot, and a
     * renderer handed what it cannot play shows a black screen and reports
     * nothing -- which nobody can tell from the app being broken.
     */
    data class CannotPlay(val offered: List<String>) : CastOutcome
}

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

    /**
     * Plays [item] -- or, where the screen says what it plays, the first of
     * [CastItem.choices] it does.
     */
    suspend fun play(device: CastDevice, item: CastItem): CastOutcome
    suspend fun stop()

    /** What is playing, and null when nothing is or the screen says nothing. */
    val playback: StateFlow<Playback?> get() = NOTHING_PLAYING

    suspend fun pause() = Unit
    suspend fun resume() = Unit
    suspend fun seek(seconds: Long) = Unit

    /** 0 to 100. */
    suspend fun volume(percent: Int) = Unit

    /**
     * A trail of one attempt, sent as a single crash-reports event when it
     * ends: every step of casting fails in silence otherwise, at the far end.
     */
    fun begin() = Unit
    fun note(message: String) = Unit
    fun report(outcome: String) = Unit
}

private val NOTHING_PLAYING = MutableStateFlow<Playback?>(null).asStateFlow()

/** What a phone without Play Services gets, and iOS until it has a sender. */
class NoCaster : Caster {
    override val available = false
    override val devices = MutableStateFlow(emptyList<CastDevice>()).asStateFlow()
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override suspend fun play(device: CastDevice, item: CastItem): CastOutcome = CastOutcome.Started
    override suspend fun stop() = Unit
}
