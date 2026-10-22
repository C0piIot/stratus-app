package dev.stratus.core.cast.dlna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsdpTest {

    @Test
    fun theSearchAsksForRenderersOnTheMulticastGroup() {
        val search = Ssdp.search()
        assertTrue(search.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("HOST: 239.255.255.250:1900\r\n" in search)
        assertTrue("MAN: \"ssdp:discover\"\r\n" in search)
        assertTrue("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" in search)
        assertTrue(search.endsWith("\r\n\r\n"), "a search is a request with no body, so it ends on an empty line")
    }

    @Test
    fun anAnswerIsASighting() {
        val answer = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "Location: http://192.168.1.40:49152/description.xml\r\n" +
            "SERVER: Linux/6.1 UPnP/1.0 GMediaRender/0.0.9\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:5a1c0d6e-0000-0000-0000-b827eb000001::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals(
            Sighting(
                location = "http://192.168.1.40:49152/description.xml",
                usn = "uuid:5a1c0d6e-0000-0000-0000-b827eb000001::urn:schemas-upnp-org:device:MediaRenderer:1",
            ),
            Ssdp.sightingOf(answer),
        )
    }

    @Test
    fun aRendererJoiningIsASightingAndOneLeavingIsNot() {
        fun notify(nts: String) = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "LOCATION: http://192.168.1.41:9197/dmr\r\n" +
            "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "NTS: $nts\r\n" +
            "USN: uuid:tv::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.41:9197/dmr", Ssdp.sightingOf(notify("ssdp:alive"))?.location)
        assertNull(Ssdp.sightingOf(notify("ssdp:byebye")))
    }

    @Test
    fun aNewerRendererAnswersAsItself() {
        val answer = "HTTP/1.1 200 OK\r\nLOCATION: http://tv/d.xml\r\nST: urn:schemas-upnp-org:device:MediaRenderer:3\r\n\r\n"
        assertEquals("http://tv/d.xml", Ssdp.sightingOf(answer)?.location)
    }

    @Test
    fun whatIsNotARendererIsNothing() {
        // A router answering a search somebody else sent, on the same group.
        val router = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1/igd.xml\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
        assertNull(Ssdp.sightingOf(router))
        assertNull(Ssdp.sightingOf("M-SEARCH * HTTP/1.1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
        assertNull(Ssdp.sightingOf("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"))
        assertNull(Ssdp.sightingOf(""))
    }
}
