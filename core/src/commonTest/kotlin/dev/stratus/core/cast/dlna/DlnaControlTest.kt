package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DlnaControlTest {

    private val renderer = Renderer(
        id = "uuid:r",
        name = "Living room",
        avTransport = "http://r/av",
        renderingControl = "http://r/rc",
        connectionManager = "http://r/cm",
    )

    private class Sent(val url: String, val action: String?, val body: String)

    private fun control(answer: (Sent) -> Pair<HttpStatusCode, String>): Pair<DlnaControl, List<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            val s = Sent(request.url.toString(), request.headers["SOAPACTION"], request.body.toByteArray().decodeToString())
            sent += s
            val (status, body) = answer(s)
            respond(body, status, headersOf(HttpHeaders.ContentType, "text/xml"))
        }
        return DlnaControl(HttpClient(engine)) to sent
    }

    private fun envelope(inner: String) =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>$inner</s:Body></s:Envelope>"

    @Test
    fun loadingHandsTheSignedLinkOverAndStartsIt() = runTest {
        val (control, sent) = control { HttpStatusCode.OK to envelope("<u:Ok xmlns:u=\"urn:x\"/>") }
        val item = CastItem("https://s/files/a%20b.mkv?k=k1.x.y&hls=index.m3u8", "application/vnd.apple.mpegurl", "a & b.mkv")
        control.load(renderer, Pick(item, "http-get:*:application/vnd.apple.mpegurl:*"))

        assertEquals(listOf("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""), sent.map { it.action })
        assertTrue(sent.all { it.url == "http://r/av" })
        val body = sent[0].body
        assertTrue("<InstanceID>0</InstanceID><CurrentURI>https://s/files/a%20b.mkv?k=k1.x.y&amp;hls=index.m3u8</CurrentURI>" in body)
        // DIDL travels as the text of an argument, so it is escaped twice: the
        // ampersand in the URL is &amp;amp; and the title's is too.
        assertTrue("&lt;dc:title&gt;a &amp;amp; b.mkv&lt;/dc:title&gt;" in body, body)
        assertTrue("index.m3u8&lt;/res&gt;" in body && "k=k1.x.y&amp;amp;hls" in body, body)
        assertTrue("object.item.videoItem" in body)
    }

    @Test
    fun whatItPlaysIsAskedOfTheConnectionManager() = runTest {
        val (control, sent) = control {
            HttpStatusCode.OK to envelope(
                "<u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">" +
                    "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:audio/mpeg:*</Sink></u:GetProtocolInfoResponse>",
            )
        }
        assertEquals(listOf("video/mp4", "audio/mpeg"), control.protocolInfo(renderer).map { it.contentType })
        assertEquals("http://r/cm", sent.single().url)
        assertEquals(emptyList(), control.protocolInfo(renderer.copy(connectionManager = null)))
    }

    @Test
    fun aRefusalIsAFaultWithItsCode() = runTest {
        val fault = envelope(
            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>" +
                "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>714</errorCode>" +
                "<errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault>",
        )
        val (control, _) = control { HttpStatusCode.InternalServerError to fault }
        val refused = assertFailsWith<SoapFault> { control.play(renderer) }
        assertEquals(714, refused.code)
        assertEquals("Illegal MIME-type", refused.message)
        // And GetProtocolInfo refusing is a renderer that would not say.
        assertEquals(emptyList(), control.protocolInfo(renderer))
    }

    @Test
    fun anHttpRefusalWithNoEnvelopeSaysWhatItWas() = runTest {
        val (control, _) = control { HttpStatusCode.NotFound to "" }
        assertEquals("Stop answered 404", assertFailsWith<SoapFault> { control.stop(renderer) }.message)
    }

    @Test
    fun seekingAndVolumeAreWrittenAsUpnpWritesThem() = runTest {
        val (control, sent) = control { HttpStatusCode.OK to envelope("<u:Ok xmlns:u=\"urn:x\"/>") }
        control.seek(renderer, 3725)
        control.pause(renderer)
        assertTrue(control.volume(renderer, 140))
        assertFalse(control.volume(renderer.copy(renderingControl = null), 10))
        assertTrue("<Unit>REL_TIME</Unit><Target>1:02:05</Target>" in sent[0].body)
        assertTrue("#Pause" in sent[1].action.orEmpty())
        assertEquals("http://r/rc", sent[2].url)
        assertTrue("<DesiredVolume>100</DesiredVolume>" in sent[2].body)
    }

    @Test
    fun aDescriptionIsFetchedAndRead() = runTest {
        val (control, _) = control {
            if (it.url.endsWith("desc.xml")) {
                HttpStatusCode.OK to "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>TV</friendlyName><UDN>uuid:t</UDN>" +
                    "<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service></serviceList></device></root>"
            } else {
                HttpStatusCode.NotFound to ""
            }
        }
        assertEquals("http://tv:1/av", control.describe("http://tv:1/desc.xml")?.avTransport)
        assertEquals(null, control.describe("http://tv:1/gone"))
    }

    @Test
    fun aPositionIsHoursMinutesSeconds() {
        assertEquals("0:00:00", hms(-5))
        assertEquals("0:01:05", hms(65))
        assertEquals("12:00:09", hms(43209))
    }
}
