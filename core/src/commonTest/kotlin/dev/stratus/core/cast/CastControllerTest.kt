package dev.stratus.core.cast

import dev.stratus.core.dav.DavResource
import dev.stratus.core.net.Credentials
import dev.stratus.core.share.LinkSupport
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeCaster(override val available: Boolean = true) : Caster {
    override val devices = MutableStateFlow(listOf(CastDevice("tv-1", "The television")))
    var discovering = false
    var played: CastItem? = null
    var answer: CastOutcome = CastOutcome.Started
    var throws: Exception? = null
    val controls = mutableListOf<String>()
    val notes = mutableListOf<String>()
    override fun note(message: String) { notes += message }
    override suspend fun pause() { controls += "pause" }
    override suspend fun resume() { controls += "resume" }
    override suspend fun seek(seconds: Long) { controls += "seek $seconds" }
    override suspend fun volume(percent: Int) { controls += "volume $percent" }
    override fun startDiscovery() { discovering = true }
    override fun stopDiscovery() { discovering = false }
    override suspend fun play(device: CastDevice, item: CastItem): CastOutcome {
        played = item
        throws?.let { throw it }
        return answer
    }
    override suspend fun stop() { played = null }
}

class CastControllerTest {

    // The origin, as sign-in settles on it, with paths under the files
    // collection -- which is what a signature is relative to.
    private val links = ShareLinks("https://host/", Credentials("edu", "secret"))
    private val caster = FakeCaster()

    /**
     * A server that answers a signed link, unless a test says otherwise, and
     * answers it as [hlsType] when HLS is asked for -- Stratus's playlist, or
     * the file itself from a server that ignores the query.
     */
    private fun support(status: HttpStatusCode = HttpStatusCode.OK, hlsType: String = HLS_TYPE, transcodes: Boolean = true) =
        LinkSupport(
            HttpClient(
                MockEngine { request ->
                    val format = request.url.parameters["format"]
                    val type = when {
                        request.url.parameters.contains("hls") -> hlsType
                        format != null && transcodes -> mapOf("mp3" to "audio/mpeg", "aac" to "audio/aac").getValue(format)
                        format != null -> "audio/opus"
                        else -> "video/mp4"
                    }
                    respond("", status, headersOf(HttpHeaders.ContentType, type))
                },
            ),
        )

    private fun controller(
        scope: TestScope,
        pinned: Boolean = false,
        caster: Caster = this.caster,
        support: LinkSupport = support(),
    ) = CastController(caster, links, pinned, support, scope) { 1_700_000_000 }

    private fun file(name: String, type: String?) =
        DavResource(path = "/files/album/$name", isDirectory = false, contentType = type)

    @Test
    fun aPhotographGoesAsTheServersJpegAndNotAsItself() = runTest {
        // A Chromecast cannot read a HEIC, which is what an iPhone records and
        // what this app uploads untouched. The rendering is the whole point.
        val item = chosenFor(this, "IMG_1.HEIC", "image/heic")
        assertTrue(item.url.contains("/thumb/album/IMG_1.HEIC"), "was ${item.url}")
        assertTrue(item.url.contains("size=1200"), "was ${item.url}")
        assertEquals("image/jpeg", item.contentType)
    }

    @Test
    fun aVideoGoesAsHls() = runTest {
        // WebDAV does not say whether this is an HEVC most Chromecasts cannot
        // decode, so the server's playlist is what lets the receiver choose.
        val item = chosenFor(this, "clip.mp4", "video/mp4")
        assertTrue(item.url.contains("/files/album/clip.mp4?k="), "was ${item.url}")
        assertTrue(item.url.endsWith("&hls=index.m3u8"), "was ${item.url}")
        assertEquals(HLS_TYPE, item.contentType)
    }

    @Test
    fun aVideoGoesAsItselfWhereTheServerDoesNotDoHls() = runTest {
        // A server that ignores the query answers with the film, not a playlist.
        val cast = controller(this, support = support(hlsType = "video/mp4"))
        cast.offer(file("clip.mp4", "video/mp4"))
        val item = (cast.state.first { it is CastState.Choosing } as CastState.Choosing).item

        assertTrue(!item.url.contains("hls="), "was ${item.url}")
        assertEquals("video/mp4", item.contentType)
    }

    @Test
    fun aTrackGoesAsItself() = runTest {
        val item = chosenFor(this, "song.flac", "audio/flac")
        assertTrue(!item.url.contains("hls="), "was ${item.url}")
        assertEquals("audio/flac", item.contentType)
    }

    @Test
    fun thereIsNothingToOfferForADocument() = runTest {
        val cast = controller(this)
        assertTrue(!cast.canCast(file("notes.txt", "text/plain")))
        cast.offer(file("notes.txt", "text/plain"))
        assertEquals(CastState.Idle, cast.state.value, "offered something no screen can read")
    }

    @Test
    fun aPinnedCertificateIsWarnedAboutBeforeAnythingElseHappens() = runTest {
        // The television has nobody to ask about a certificate, so it fetches
        // nothing and says nothing. Predicting it is the only way anybody knows.
        val cast = controller(this, pinned = true)
        cast.offer(file("clip.mp4", "video/mp4"))

        assertTrue(cast.state.value is CastState.CertificateIsOnlyTrustedHere, "was ${cast.state.value}")
        assertTrue(!caster.discovering, "started looking for screens behind the warning")
    }

    @Test
    fun theWarningIsAWarningAndNotARefusal() = runTest {
        // A pin is only consulted when system validation fails, so a server
        // given a real certificate since would be warned about for nothing.
        val cast = controller(this, pinned = true)
        cast.offer(file("clip.mp4", "video/mp4"))
        cast.goOnAnyway()
        cast.state.first { it is CastState.Choosing }

        assertTrue(cast.state.value is CastState.Choosing)
        assertTrue(caster.discovering)
    }

    @Test
    fun choosingAScreenPlaysOnItAndStopsLookingForMore() = runTest {
        val cast = controller(this)
        cast.offer(file("clip.mp4", "video/mp4"))
        cast.state.first { it is CastState.Choosing }
        cast.playOn(CastDevice("tv-1", "The television"))
        testScheduler.advanceUntilIdle()

        assertTrue(cast.state.value is CastState.Playing)
        assertEquals(HLS_TYPE, caster.played?.contentType)
        assertTrue(!caster.discovering, "kept the radio on while playing")
    }

    @Test
    fun aScreenThatPlaysNoneOfItIsSaidSo() = runTest {
        caster.answer = CastOutcome.CannotPlay(listOf(HLS_TYPE, "video/x-matroska"), listOf("http-get video/mp4"))
        val cast = controller(this)
        cast.offer(file("film.mkv", "video/x-matroska"))
        cast.state.first { it is CastState.Choosing }
        cast.playOn(CastDevice("tv-1", "The television"))
        testScheduler.advanceUntilIdle()

        val said = cast.state.value
        assertTrue(said is CastState.ScreenCannotPlay, "was $said")
        assertEquals(listOf(HLS_TYPE, "video/x-matroska"), said.offered)
        assertEquals("The television", said.device.name)
        // What it listed instead goes in the trail (#167).
        assertTrue(caster.notes.any { "it lists [http-get video/mp4]" in it }, "notes: ${caster.notes}")
    }

    @Test
    fun aScreenThatRefusesIsSaidSoWithItsWords() = runTest {
        caster.answer = CastOutcome.Failed("error 714: Illegal MIME-type")
        val cast = controller(this)
        cast.offer(file("clip.mp4", "video/mp4"))
        cast.state.first { it is CastState.Choosing }
        cast.playOn(CastDevice("tv-1", "The television"))
        testScheduler.advanceUntilIdle()
        assertEquals("error 714: Illegal MIME-type", (cast.state.value as CastState.ScreenFailed).reason)
    }

    @Test
    fun aScreenThatThrowsDoesNotTakeTheAppDown() = runTest {
        // STRATUS-APP-6: a renderer that started answering and never finished.
        caster.throws = RuntimeException("Socket timeout has expired")
        val cast = controller(this)
        cast.offer(file("clip.mp4", "video/mp4"))
        cast.state.first { it is CastState.Choosing }
        cast.playOn(CastDevice("tv-1", "The television"))
        testScheduler.advanceUntilIdle()
        assertEquals("Socket timeout has expired", (cast.state.value as CastState.ScreenFailed).reason)
    }

    @Test
    fun theChoicesAreThePlaylistThenTheFile() = runTest {
        val cast = controller(this)
        cast.offer(file("film.mkv", "video/x-matroska"))
        val choosing = cast.state.first { it is CastState.Choosing } as CastState.Choosing
        assertEquals(listOf(HLS_TYPE, "video/x-matroska"), choosing.item.choices.map { it.contentType })
    }

    @Test
    fun aTrackIsItselfThenItsTranscodes() = runTest {
        // stratus-app#166: a renderer that lists no Opus is sent the MP3.
        val cast = controller(this)
        cast.offer(file("song.opus", "audio/opus"))
        val choosing = cast.state.first { it is CastState.Choosing } as CastState.Choosing
        assertEquals(listOf("audio/opus", "audio/mpeg", "audio/aac"), choosing.item.choices.map { it.contentType })
        assertTrue(choosing.item.choices[1].url.endsWith("&format=mp3&estimateContentLength=true"))
    }

    @Test
    fun aTranscodeTheServerDoesNotMakeIsNotOffered() = runTest {
        // A server that ignores ?format= answers the Opus itself, which a
        // renderer told it is an MP3 would fail on.
        val cast = controller(this, support = support(transcodes = false))
        cast.offer(file("song.opus", "audio/opus"))
        val choosing = cast.state.first { it is CastState.Choosing } as CastState.Choosing
        assertEquals(listOf("audio/opus"), choosing.item.choices.map { it.contentType })
    }

    @Test
    fun theControlsReachTheScreenOnlyWhileSomethingPlays() = runTest {
        val cast = controller(this)
        cast.pause()
        cast.seekTo(30)
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), caster.controls, "a stale button reached a screen")

        cast.offer(file("clip.mp4", "video/mp4"))
        cast.state.first { it is CastState.Choosing }
        cast.playOn(CastDevice("tv-1", "The television"))
        cast.pause()
        cast.resume()
        cast.seekTo(-5)
        cast.setVolume(140)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("pause", "resume", "seek 0", "volume 100"), caster.controls)
    }

    @Test
    fun aPositionReadsTheWayAPlayerShowsIt() {
        assertEquals("0:00", clockOf(-1))
        assertEquals("3:07", clockOf(187))
        assertEquals("1:02:05", clockOf(3725))
    }

    @Test
    fun aPhoneWithoutPlayServicesIsOfferedNothing() = runTest {
        val cast = controller(this, caster = FakeCaster(available = false))
        assertTrue(!cast.available)
        assertTrue(!cast.canCast(file("clip.mp4", "video/mp4")))
    }

    @Test
    fun aServerThatDoesNotDoLinksIsSaidSoRatherThanBlamedOnTheTelevision() = runTest {
        // Every other WebDAV server, which is most of them: the signature means
        // nothing there, and a television would simply never start.
        val cast = controller(this, support = support(HttpStatusCode.SeeOther))
        cast.offer(file("clip.mp4", "video/mp4"))
        cast.state.first { it is CastState.TheServerDoesNotDoLinks }

        assertEquals(CastState.TheServerDoesNotDoLinks, cast.state.value)
        assertTrue(!caster.discovering, "went looking for screens for a link that means nothing")
        // And having learned it, it stops being offered at all.
        assertTrue(!cast.canCast(file("clip.mp4", "video/mp4")))
    }

    @Test
    fun aServerThatFailsIsNotTakenForOneThatDoesNotDoLinks() = runTest {
        // A 502 from a proxy in front of a server that is restarting, which is
        // how this was found: it hid casting until the app was restarted.
        val support = support(HttpStatusCode.BadGateway)
        val cast = controller(this, support = support)
        cast.offer(file("IMG_1.jpg", "image/jpeg"))
        cast.state.first { it is CastState.TheServerDidNotAnswer }

        assertTrue(!caster.discovering, "went looking for screens for a link nobody vouched for")
        assertTrue(cast.canCast(file("IMG_1.jpg", "image/jpeg")), "one failure took casting away")
    }

    /** What a screen would be handed, which is the decision under test. */
    private suspend fun chosenFor(scope: TestScope, name: String, type: String): CastItem {
        val cast = controller(scope)
        cast.offer(file(name, type))
        return (cast.state.first { it is CastState.Choosing } as CastState.Choosing).item
    }
}
