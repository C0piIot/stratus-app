package dev.stratus.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BasicAuthTest {

    @Test
    fun matchesTheExampleInRfc7617() {
        assertEquals(
            "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==",
            basicAuthHeader(Credentials("Aladdin", "open sesame")),
        )
    }

    @Test
    fun encodesAPasswordAsUtf8() {
        // The trap is Latin-1, which works until somebody's password has an ñ in
        // it and then fails in a way that looks like a wrong password.
        assertEquals("Basic ZWR1OmNvbnRyYXNlw7Fh", basicAuthHeader(Credentials("edu", "contraseña")))
    }

    @Test
    fun copesWithAnEmptyPassword() {
        assertEquals("Basic ZWR1Og==", basicAuthHeader(Credentials("edu", "")))
    }

    @Test
    fun refusesAUsernameBasicAuthCannotExpress() {
        // The server splits on the first colon, so the rest would be read as the
        // password. Better said at the keyboard than seen as a login failure.
        assertFalse(usernameIsUsable("a:b"))
        assertTrue(usernameIsUsable("edu"))
    }

    @Test
    fun keepsAPasswordOutOfWhatGetsPrinted() {
        assertFalse(Credentials("edu", "hunter2").toString().contains("hunter2"))
    }
}
