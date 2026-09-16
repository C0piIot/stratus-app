package dev.stratus.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerAddressTest {

    private fun valid(typed: String): ServerAddress {
        val parsed = parseServerAddress(typed)
        assertTrue(parsed is ParsedAddress.Valid, "$typed was $parsed")
        return parsed.address
    }

    private fun problem(typed: String): AddressProblem {
        val parsed = parseServerAddress(typed)
        assertTrue(parsed is ParsedAddress.Invalid, "$typed was $parsed")
        return parsed.problem
    }

    @Test
    fun readsTheFormsPeopleActuallyType() {
        assertEquals(ServerAddress(null, "host", null, null), valid("host"))
        assertEquals(ServerAddress(null, "host", 8080, null), valid("host:8080"))
        assertEquals(ServerAddress(Scheme.Http, "host", null, null), valid("http://host"))
        assertEquals(ServerAddress(Scheme.Https, "host", null, null), valid("https://host/"))
        assertEquals(ServerAddress(null, "192.168.1.10", 8080, null), valid("192.168.1.10:8080"))
        assertEquals(ServerAddress(Scheme.Https, "host", 8443, "/dav/"), valid("https://host:8443/dav"))
    }

    @Test
    fun treatsNoPathAndABareSlashAsTheSameThing() {
        // Both mean "I did not tell you a path", which is what licenses looking
        // for /dav/. A literal "/" would rule that out for no reason.
        assertEquals(null, valid("host").path)
        assertEquals(null, valid("host/").path)
        assertEquals(null, valid("host//").path)
        assertEquals("/dav/", valid("host/dav").path)
        assertEquals("/dav/", valid("host/dav/").path)
        assertEquals("/remote.php/dav/", valid("host/remote.php/dav").path)
    }

    @Test
    fun lowercasesTheSchemeAndHostButNeverThePath() {
        val address = valid("HTTPS://Host.Example/DAV")
        assertEquals(Scheme.Https, address.scheme)
        assertEquals("host.example", address.host)
        // A case-sensitive filesystem behind the server will not forgive this.
        assertEquals("/DAV/", address.path)
    }

    @Test
    fun keepsAnIpv6LiteralInItsBrackets() {
        assertEquals(ServerAddress(null, "[2001:db8::1]", 8080, null), valid("[2001:db8::1]:8080"))
        assertEquals(ServerAddress(null, "[2001:db8::1]", null, null), valid("[2001:db8::1]"))
    }

    @Test
    fun trimsWhatSurroundsItButNotWhatIsInside() {
        assertEquals("host", valid("  host  ").host)
        assertTrue(problem("ho st") is AddressProblem.NotAHost)
    }

    @Test
    fun refusesAPortThatIsNotOne() {
        assertEquals(AddressProblem.BadPort("abc"), problem("host:abc"))
        assertEquals(AddressProblem.BadPort("70000"), problem("host:70000"))
        assertEquals(AddressProblem.BadPort("0"), problem("host:0"))
        assertEquals(AddressProblem.BadPort(""), problem("host:"))
    }

    @Test
    fun refusesRatherThanRepairsWhatWouldHideAMistake() {
        // Pasted a link to a page rather than the server. Stripping it quietly
        // would sign them in somewhere they did not mean.
        assertEquals(AddressProblem.QueryOrFragment, problem("https://host/dav/?x=1"))
        assertEquals(AddressProblem.QueryOrFragment, problem("https://host/dav/#top"))
        assertEquals(AddressProblem.CredentialsInUrl, problem("https://user:pass@host"))
        assertEquals(AddressProblem.Empty, problem("   "))
        assertEquals(AddressProblem.UnknownScheme("ftp"), problem("ftp://host"))
    }
}
