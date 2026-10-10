package dev.stratus.core.server

import dev.stratus.core.net.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerBlobTest {

    private fun roundTrip(password: String) {
        val instance = Server("https://host/dav/", "edu", "Camera", setOf("DCIM"))
        val decoded = ServerBlob.decode(ServerBlob.encode(instance, Credentials("edu", password)))
        assertEquals(instance to Credentials("edu", password), decoded, "password was ${password.length} chars")
    }

    @Test
    fun survivesEveryCharacterAPasswordMayContain() {
        // Each of these breaks a format that picks a delimiter instead of counting.
        roundTrip("plain")
        roundTrip("")
        roundTrip("with a newline\nin it")
        roundTrip("with\ttabs")
        roundTrip("colons:everywhere:3:4")
        roundTrip("emoji 📷 and ñ")
        roundTrip("10:fake length prefix")
    }

    @Test
    fun carriesTheSettingsThatBelongToTheInstance() {
        val instance = Server("https://host/dav/", "edu", "Pictures", setOf("DCIM", "Camera"))
        val (read, _) = ServerBlob.decode(ServerBlob.encode(instance, Credentials("edu", "pw")))!!
        assertEquals("Pictures", read.backupRoot)
        assertEquals(setOf("DCIM", "Camera"), read.sources)
        // Sources are the switch now (stratus-app#131): chosen means on.
        assertEquals(true, read.backupEnabled)
    }

    @Test
    fun carriesWhetherMobileDataMayBeSpent() {
        val instance = Server("https://host/", "edu", onlyOnWifi = false)
        val (read, _) = ServerBlob.decode(ServerBlob.encode(instance, Credentials("edu", "pw")))!!
        assertEquals(false, read.onlyOnWifi)
    }

    @Test
    fun aRecordWrittenBeforeTheSettingExistedIsStillRead() {
        // Signing somebody out costs them the record of what has been backed
        // up, so the next pass walks the whole server to rebuild it. Paying
        // that for a field with a default would be absurd (stratus-app#133).
        val before = listOf("5", "https://host/", "edu", "pw", "files/phone_backup", "DCIM")
            .joinToString("") { it.length.toString() + ":" + it }

        val (read, credentials) = ServerBlob.decode(before)!!
        assertEquals("https://host/", read.baseUrl)
        assertEquals(setOf("DCIM"), read.sources)
        assertEquals(Credentials("edu", "pw"), credentials)
        // The safe half of the default: a phone that was sending over mobile
        // data stops rather than carrying on unasked.
        assertEquals(true, read.onlyOnWifi)
    }

    @Test
    fun treatsAnythingItCannotReadAsSignedOut() {
        // Never an exception: a record from a version that wrote a different
        // shape means "sign in again", not a crash on first launch.
        assertNull(ServerBlob.decode(""))
        assertNull(ServerBlob.decode("nonsense"))
        assertNull(ServerBlob.decode("999:overrun"))
        // The shape the store used before instances existed.
        assertNull(ServerBlob.decode("1:24:abcd3:edu2:pw"))
    }
}
