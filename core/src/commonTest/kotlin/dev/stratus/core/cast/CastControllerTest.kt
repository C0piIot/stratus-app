package dev.stratus.core.cast

import dev.stratus.core.dav.DavResource
import dev.stratus.core.net.Credentials
import dev.stratus.core.share.ShareLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeCaster(override val available: Boolean = true) : Caster {
    override val devices = MutableStateFlow(listOf(CastDevice("tv-1", "The television")))
    var discovering = false
    var played: CastItem? = null
    override fun startDiscovery() { discovering = true }
    override fun stopDiscovery() { discovering = false }
    override suspend fun play(device: CastDevice, item: CastItem) { played = item }
    override suspend fun stop() { played = null }
}

class CastControllerTest {

    private val links = ShareLinks("https://host/dav/", Credentials("edu", "secret"))
    private val caster = FakeCaster()

    private fun controller(scope: TestScope, pinned: Boolean = false, caster: Caster = this.caster) =
        CastController(caster, links, pinned, scope) { 1_700_000_000 }

    private fun file(name: String, type: String?) =
        DavResource(path = "/album/$name", isDirectory = false, contentType = type)

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
    fun aVideoGoesAsItself() = runTest {
        val item = chosenFor(this, "clip.mp4", "video/mp4")
        assertTrue(item.url.contains("/files/album/clip.mp4"), "was ${item.url}")
        assertEquals("video/mp4", item.contentType)
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

        assertTrue(cast.state.value is CastState.Choosing)
        assertTrue(caster.discovering)
    }

    @Test
    fun choosingAScreenPlaysOnItAndStopsLookingForMore() = runTest {
        val cast = controller(this)
        cast.offer(file("clip.mp4", "video/mp4"))
        cast.playOn(CastDevice("tv-1", "The television"))
        testScheduler.advanceUntilIdle()

        assertTrue(cast.state.value is CastState.Playing)
        assertEquals("video/mp4", caster.played?.contentType)
        assertTrue(!caster.discovering, "kept the radio on while playing")
    }

    @Test
    fun aPhoneWithoutPlayServicesIsOfferedNothing() = runTest {
        val cast = controller(this, caster = FakeCaster(available = false))
        assertTrue(!cast.available)
        assertTrue(!cast.canCast(file("clip.mp4", "video/mp4")))
    }

    /** What a screen would be handed, which is the decision under test. */
    private fun chosenFor(scope: TestScope, name: String, type: String): CastItem {
        val cast = controller(scope)
        cast.offer(file(name, type))
        return (cast.state.value as CastState.Choosing).item
    }
}
