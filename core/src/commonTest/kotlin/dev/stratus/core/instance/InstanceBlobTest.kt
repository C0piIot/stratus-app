package dev.stratus.core.instance

import dev.stratus.core.net.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstanceBlobTest {

    private fun roundTrip(password: String) {
        val instance = Instance("abc123", "https://host/dav/", "edu", "Camera", backupEnabled = true)
        val decoded = InstanceBlob.decode(InstanceBlob.encode(instance, Credentials("edu", password)))
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
        val instance = Instance("id", "https://host/dav/", "edu", "Pictures", backupEnabled = false)
        val (read, _) = InstanceBlob.decode(InstanceBlob.encode(instance, Credentials("edu", "pw")))!!
        assertEquals("Pictures", read.backupRoot)
        assertEquals(false, read.backupEnabled)
    }

    @Test
    fun treatsAnythingItCannotReadAsSignedOut() {
        // Never an exception: a record from a version that wrote a different
        // shape means "sign in again", not a crash on first launch.
        assertNull(InstanceBlob.decode(""))
        assertNull(InstanceBlob.decode("nonsense"))
        assertNull(InstanceBlob.decode("999:overrun"))
        // The shape the store used before instances existed.
        assertNull(InstanceBlob.decode("1:24:abcd3:edu2:pw"))
    }
}
