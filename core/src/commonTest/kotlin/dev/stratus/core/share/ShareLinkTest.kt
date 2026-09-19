package dev.stratus.core.share

import dev.stratus.core.net.Credentials
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareLinkTest {

    private val links = ShareLinks("https://host/dav/", Credentials("edu", "secret"))

    private fun tokenOf(url: String) = url.substringAfter("?k=")

    /** What the signature is actually over, as opposed to what the URL carries. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun signedPathOf(url: String): String {
        val part = tokenOf(url).split(".")[2]
        return Base64.UrlSafe.decode(part.padEnd(part.length + (4 - part.length % 4) % 4, '=')).decodeToString()
    }

    @Test
    fun signsWhatAnIndependentImplementationSigns() {
        // Computed with Python's hmac and base64 rather than by running this
        // code: a golden value this implementation agreed with is worth nothing.
        // The conformance suite proves the server agrees too; this catches the
        // same mistake in seconds instead of needing a container.
        val url = links.link("/album/a b.jpg", isDirectory = false, life = ShareLife.Forever, nowEpochSeconds = 0)
        assertEquals("k1.ZWR1.YWxidW0vYSBiLmpwZw.f.0.gmeIwaQGF0_j40VunU4k8hYm-ztH1O1kaT3NL8R-sDE", tokenOf(url))
    }

    @Test
    fun aFolderIsADifferentClaimFromAFile() {
        val file = tokenOf(links.link("/album", isDirectory = false, life = ShareLife.Forever, nowEpochSeconds = 0))
        val folder = tokenOf(links.link("/album", isDirectory = true, life = ShareLife.Forever, nowEpochSeconds = 0))
        assertEquals("f", file.split(".")[3])
        assertEquals("d", folder.split(".")[3])
        // And the signature covers the difference, or one could be filed into
        // the other and a shared file would open its whole folder.
        assertTrue(file.split(".")[5] != folder.split(".")[5])
    }

    @Test
    fun aLifeIsADeadlineAndForeverIsAZero() {
        val day = tokenOf(links.link("/a", false, ShareLife.ADay, nowEpochSeconds = 1_700_000_000))
        assertEquals("1700086400", day.split(".")[4])
        val forever = tokenOf(links.link("/a", false, ShareLife.Forever, nowEpochSeconds = 1_700_000_000))
        assertEquals("0", forever.split(".")[4])
    }

    @Test
    fun theUrlIsTheWebDavOneAndEncodesWhatAPathCannotCarry() {
        // The mount rather than the web UI: this app already holds this address,
        // and a mount is not the surface that changes shape.
        val url = links.link("/álbum/a b&c.jpg", false, ShareLife.Forever, 0)
        assertTrue(url.startsWith("https://host/dav/"), "was $url")
        // Signed raw, sent encoded: the server unescapes before it verifies.
        assertTrue("%C3%A1lbum" in url && "a%20b" in url, "was $url")
        assertEquals("álbum/a b&c.jpg", signedPathOf(url))
    }

    @Test
    fun aPortThatIsNotTheDefaultSurvives() {
        val other = ShareLinks("http://192.168.1.10:8080/dav/", Credentials("edu", "secret"))
        assertTrue(other.link("/a", false, ShareLife.Forever, 0).startsWith("http://192.168.1.10:8080/dav/a?"))
    }

    @Test
    fun anotherPasswordSignsSomethingElseEntirely() {
        // The property the whole thing rests on, and the reason changing the
        // password withdraws every link ever sent.
        val moved = ShareLinks("https://host/dav/", Credentials("edu", "other"))
        assertTrue(
            tokenOf(links.link("/a", false, ShareLife.Forever, 0)) !=
                tokenOf(moved.link("/a", false, ShareLife.Forever, 0)),
        )
    }

    @Test
    fun aThumbnailIsTheOneThingStillOffTheOrigin() {
        // There is no thumbnail in WebDAV, so it has nowhere else to live -- and
        // it takes the same signature, over the same path.
        val url = links.thumbnail("/album/IMG_1.HEIC", 1200, ShareLife.ADay, 0)
        assertTrue(url.startsWith("https://host/thumb/album/IMG_1.HEIC?"), "was $url")
        assertTrue("size=1200" in url)
        assertEquals(
            tokenOf(links.link("/album/IMG_1.HEIC", false, ShareLife.ADay, 0)),
            url.substringAfter("&k="),
        )
    }
}
