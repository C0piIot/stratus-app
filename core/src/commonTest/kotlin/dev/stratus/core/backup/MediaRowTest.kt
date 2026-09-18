package dev.stratus.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaRowTest {

    private fun row(
        taken: Long? = utcMillis(2026, 9, 18, 9, 30),
        added: Long = utcMillis(2026, 9, 19) / 1_000,
        name: String? = "IMG_0001.HEIC",
        size: Long = 4_000,
    ) = MediaRow("42", name, size, "bucket-1", taken, added)

    @Test
    fun believesWhenThePictureWasTakenOverWhenThePhoneNoticedIt() {
        assertEquals(utcMillis(2026, 9, 18, 9, 30), assetOf(row()).capturedAtEpochMs)
    }

    @Test
    fun fallsBackForEverythingTheCameraDidNotWrite() {
        // A screenshot, a download, a video out of a messaging app: no DATE_TAKEN
        // at all. Filing those under the day the app was installed would put half
        // a library in one folder.
        assertEquals(utcMillis(2026, 9, 19), assetOf(row(taken = null)).capturedAtEpochMs)
        assertEquals(utcMillis(2026, 9, 19), assetOf(row(taken = 0)).capturedAtEpochMs)
    }

    @Test
    fun givesANamelessRowSomewhereToLand() {
        // Dropping it would lose a photograph; the digest keeps two of them apart.
        assertEquals("untitled", assetOf(row(name = null)).originalName)
        assertEquals("untitled", assetOf(row(name = "  ")).originalName)
    }

    @Test
    fun carriesWhatTheRestOfTheSystemIdentifiesItBy() {
        val asset = assetOf(row())
        assertEquals("42", asset.localId)
        assertEquals(4_000, asset.sizeBytes)
        // Android has no Live Photos, so there is never a second half here.
        assertEquals(null, asset.motion)
    }
}
