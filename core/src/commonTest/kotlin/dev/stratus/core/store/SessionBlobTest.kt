package dev.stratus.core.store

import dev.stratus.core.net.Credentials
import dev.stratus.core.signin.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionBlobTest {

    private fun roundTrip(password: String) {
        val session = Session("https://host/dav/", "edu")
        val decoded = SessionBlob.decode(SessionBlob.encode(session, Credentials("edu", password)))
        assertEquals(session to Credentials("edu", password), decoded, "password was ${password.length} chars")
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
    fun readsBackTheUsernameAndUrlUnchanged() {
        val session = Session("http://192.168.1.10:8080/dav/", "a name with spaces")
        val (readSession, credentials) = SessionBlob.decode(
            SessionBlob.encode(session, Credentials("a name with spaces", "pw")),
        )!!
        assertEquals(session, readSession)
        assertEquals("a name with spaces", credentials.username)
    }

    @Test
    fun treatsAnythingItCannotReadAsSignedOut() {
        // Never an exception: a store that came back corrupt, or from a version
        // that wrote a different shape, means "sign in again" and not a crash.
        assertNull(SessionBlob.decode(""))
        assertNull(SessionBlob.decode("nonsense"))
        assertNull(SessionBlob.decode("5:short"))
        assertNull(SessionBlob.decode("999:overrun"))
        assertNull(SessionBlob.decode("1:2" + "4:http"))
        assertNull(SessionBlob.decode("-1:x"))
    }
}
