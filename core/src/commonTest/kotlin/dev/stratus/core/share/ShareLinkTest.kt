package dev.stratus.core.share

import dev.stratus.core.net.Credentials
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareLinkTest {

    // The origin, because that is what sign-in settles on now: a Stratus is
    // one WebDAV namespace from its root (stratus-backend#279).
    private val links = ShareLinks("https://host/", Credentials("edu", "secret"))

    /** A link for a path that can have one, which every case here uses. */
    private fun link(path: String, isDirectory: Boolean = false, life: ShareLife = ShareLife.Forever, now: Long = 0) =
        requireNotNull(links.link(path, isDirectory, life, now)) { "$path should be shareable" }

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
        //
        // Unchanged by the move to the origin, and that is the point of the
        // whole exercise: the URL grew a `files/` and the signature did not,
        // because the server signs the row.
        val url = link("/files/album/a b.jpg")
        assertEquals("k1.ZWR1.YWxidW0vYSBiLmpwZw.f.0.gmeIwaQGF0_j40VunU4k8hYm-ztH1O1kaT3NL8R-sDE", tokenOf(url))
    }

    @Test
    fun theSignatureIsTheRowAndTheUrlIsTheAddress() {
        // The one piece of this app that knows the shape of a Stratus: the
        // base is the origin, so the address carries `files/` and the token
        // must not, or the server verifies a string it never stored.
        val url = link("/files/album/one.jpg")
        assertTrue(url.startsWith("https://host/files/album/one.jpg?"), "was $url")
        assertEquals("album/one.jpg", signedPathOf(url))
    }

    @Test
    fun whatIsNotInTheFilesCollectionCannotBeSignedFor() {
        // The generated collections have no rows behind them and no gate that
        // verifies a signature, so a link to one would be a 403 nobody could
        // explain. Said here, where the button is decided, rather than found
        // out by a receiver.
        assertNull(links.link("/photos/2026/01/IMG_1.jpg", false, ShareLife.Forever, 0))
        assertNull(links.link("/playlists/Mix.m3u8", false, ShareLife.Forever, 0))
        assertNull(links.thumbnail("/photos/2026/01/IMG_1.jpg", 300, ShareLife.ADay, 0))
        // Nor the root itself, which is a listing of collections.
        assertNull(links.link("/", true, ShareLife.Forever, 0))
    }

    @Test
    fun theCollectionItselfIsTheWholeTree() {
        // Sharing `files/` is sharing everything, which the server spells as
        // the empty path with the subtree flag on.
        val url = link("/files", isDirectory = true)
        assertEquals("", signedPathOf(url))
        assertEquals("d", tokenOf(url).split(".")[3])
    }

    @Test
    fun aFolderIsADifferentClaimFromAFile() {
        val file = tokenOf(link("/files/album"))
        val folder = tokenOf(link("/files/album", isDirectory = true))
        assertEquals("f", file.split(".")[3])
        assertEquals("d", folder.split(".")[3])
        // And the signature covers the difference, or one could be filed into
        // the other and a shared file would open its whole folder.
        assertTrue(file.split(".")[5] != folder.split(".")[5])
    }

    @Test
    fun aLifeIsADeadlineAndForeverIsAZero() {
        val day = tokenOf(link("/files/a", life = ShareLife.ADay, now = 1_700_000_000))
        assertEquals("1700086400", day.split(".")[4])
        val forever = tokenOf(link("/files/a", life = ShareLife.Forever, now = 1_700_000_000))
        assertEquals("0", forever.split(".")[4])
    }

    @Test
    fun theUrlEncodesWhatAPathCannotCarry() {
        val url = link("/files/álbum/a b&c.jpg")
        assertTrue(url.startsWith("https://host/files/"), "was $url")
        // Signed raw, sent encoded: the server unescapes before it verifies.
        assertTrue("%C3%A1lbum" in url && "a%20b" in url, "was $url")
        assertEquals("álbum/a b&c.jpg", signedPathOf(url))
    }

    @Test
    fun aPortThatIsNotTheDefaultSurvives() {
        val other = ShareLinks("http://192.168.1.10:8080/", Credentials("edu", "secret"))
        val url = requireNotNull(other.link("/files/a", false, ShareLife.Forever, 0))
        assertTrue(url.startsWith("http://192.168.1.10:8080/files/a?"), "was $url")
    }

    @Test
    fun anotherPasswordSignsSomethingElseEntirely() {
        // The property the whole thing rests on, and the reason changing the
        // password withdraws every link ever sent.
        val moved = ShareLinks("https://host/", Credentials("edu", "other"))
        assertTrue(tokenOf(link("/files/a")) != tokenOf(requireNotNull(moved.link("/files/a", false, ShareLife.Forever, 0))))
    }

    @Test
    fun aThumbnailIsTheOneThingStillOffTheOrigin() {
        // There is no thumbnail in WebDAV, so it has nowhere else to live --
        // and it takes the same signature, over the same row, which means the
        // `files/` comes off its address too.
        val url = requireNotNull(links.thumbnail("/files/album/IMG_1.HEIC", 1200, ShareLife.ADay, 0))
        assertTrue(url.startsWith("https://host/thumb/album/IMG_1.HEIC?"), "was $url")
        assertTrue("size=1200" in url)
        assertEquals(tokenOf(link("/files/album/IMG_1.HEIC", life = ShareLife.ADay)), url.substringAfter("&k="))
    }
}
