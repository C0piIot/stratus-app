package dev.stratus.core.net

import kotlin.test.Test
import kotlin.test.assertEquals

class CandidatesTest {

    @Test
    fun looksAtTheRootFirstAndThenTheOldConvention() {
        // A Stratus is one WebDAV namespace from its origin, so the root is
        // both where our own server lives and where somebody else's usually
        // is. `/dav/` stays as a convention other servers use.
        val address = ServerAddress(null, "host", null, null)
        assertEquals(
            listOf("https://host/", "https://host/dav/"),
            address.candidates(Scheme.Https).map { it.baseUrl },
        )
    }

    @Test
    fun takesATypedPathAtItsWord() {
        // Somebody who wrote a path knows something we do not; guessing past it
        // would be second-guessing them against their own server.
        val address = ServerAddress(null, "host", null, "/remote.php/dav/")
        assertEquals(
            listOf("https://host/remote.php/dav/"),
            address.candidates(Scheme.Https).map { it.baseUrl },
        )
    }

    @Test
    fun leavesOffAPortThatIsAlreadyImplied() {
        assertEquals("https://host/", ServerAddress(null, "host", 443, null).candidates(Scheme.Https)[0].baseUrl)
        assertEquals("http://host/", ServerAddress(null, "host", 80, null).candidates(Scheme.Http)[0].baseUrl)
        assertEquals("https://host:8443/", ServerAddress(null, "host", 8443, null).candidates(Scheme.Https)[0].baseUrl)
    }

    @Test
    fun carriesATypedPortAcrossBothSchemes() {
        val address = ServerAddress(null, "host", 8080, null)
        assertEquals(8080, address.candidates(Scheme.Https)[0].port)
        assertEquals(8080, address.candidates(Scheme.Http)[0].port)
        assertEquals("host:8080", address.candidates(Scheme.Http)[0].hostPort)
    }
}
