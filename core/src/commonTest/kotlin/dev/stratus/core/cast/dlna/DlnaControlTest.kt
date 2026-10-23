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
        avTransport = Service("urn:schemas-upnp-org:service:AVTransport:1", "http://r/av"),
        renderingControl = Service("urn:schemas-upnp-org:service:RenderingControl:1", "http://r/rc"),
        connectionManager = Service("urn:schemas-upnp-org:service:ConnectionManager:1", "http://r/cm"),
    )

    private class Sent(val url: String, val action: String?, val body: String, val connection: String?)

    private fun control(answer: (Sent) -> Pair<HttpStatusCode, String>): Pair<DlnaControl, List<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = MockEngine { request ->
            val s = Sent(request.url.toString(), request.headers["SOAPACTION"], request.body.toByteArray().decodeToString(), request.headers[HttpHeaders.Connection])
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
        assertTrue(sent.all { it.connection == "close" }, "a renderer that mishandles keep-alive reads the next action as garbage")
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

        // A renderer that declared a later version is answered in its own.
        val newer = renderer.copy(avTransport = Service("urn:schemas-upnp-org:service:AVTransport:2", "http://r/av"))
        control.play(newer)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:2#Play\"", sent.last().action)
        assertTrue("xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:2\"" in sent.last().body)
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
        assertEquals("Play: Illegal MIME-type", refused.message, "which action it was is the first thing a report needs")
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
        assertEquals("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"", sent[2].action)
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
        assertEquals("http://tv:1/av", control.describe("http://tv:1/desc.xml")?.avTransport?.control)
        assertEquals(null, control.describe("http://tv:1/gone"))
    }

    @Test
    fun aPositionIsHoursMinutesSeconds() {
        assertEquals("0:00:00", hms(-5))
        assertEquals("0:01:05", hms(65))
        assertEquals("12:00:09", hms(43209))
    }

    @Test
    fun aPositionTheRendererDoesNotKnowIsNull() = runTest {
        val (control, _) = control {
            HttpStatusCode.OK to envelope(
                "<u:GetPositionInfoResponse xmlns:u=\"urn:x\"><RelTime>NOT_IMPLEMENTED</RelTime><TrackDuration>0:00:00</TrackDuration></u:GetPositionInfoResponse>",
            )
        }
        assertEquals(null to null, control.position(renderer))
        assertEquals(null, control.currentVolume(renderer.copy(renderingControl = null)))
        assertEquals(65L, seconds("0:01:05.250"))
        assertEquals(null, seconds("01:05"))
    }

    @Test
    fun anAnswerThatNeverFinishesIsARendererThatWouldNotSay() = runTest {
        // What a real one did: started answering GetProtocolInfo and timed out.
        val control = DlnaControl(HttpClient(MockEngine { throw RuntimeException("Socket timeout has expired") }))
        assertEquals(emptyList(), control.protocolInfo(renderer))
    }

    private val refusedPlay = envelope(
        "<s:Fault><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault>",
    )

    private fun state(now: String) = envelope("<u:GetTransportInfoResponse xmlns:u=\"urn:x\"><CurrentTransportState>$now</CurrentTransportState></u:GetTransportInfoResponse>")

    private val pick = Pick(CastItem("http://s/a.mp4?k=t", "video/mp4", "a.mp4"), "http-get:*:video/mp4:*")

    @Test
    fun aRendererThatStartsByItselfIsNotAskedToPlay() = runTest {
        // eHomeMediaCenter: a load starts it, and a Play on top is an "Invalid Action".
        val (control, sent) = control {
            when {
                it.action.orEmpty().endsWith("#Play\"") -> HttpStatusCode.InternalServerError to refusedPlay
                it.action.orEmpty().endsWith("#GetTransportInfo\"") -> HttpStatusCode.OK to state("TRANSITIONING")
                else -> HttpStatusCode.OK to envelope("<u:Ok xmlns:u=\"urn:x\"/>")
            }
        }
        control.load(renderer, pick)
        assertEquals(listOf("SetAVTransportURI", "Play", "GetTransportInfo"), sent.map { it.action.orEmpty().substringAfter('#').trimEnd('"') })
    }

    @Test
    fun aRendererStillOpeningIsAskedAgainAMomentLater() = runTest {
        var plays = 0
        val (control, _) = control {
            when {
                it.action.orEmpty().endsWith("#Play\"") -> if (++plays < 2) HttpStatusCode.InternalServerError to refusedPlay else HttpStatusCode.OK to envelope("<u:Ok xmlns:u=\"urn:x\"/>")
                it.action.orEmpty().endsWith("#GetTransportInfo\"") -> HttpStatusCode.OK to state("STOPPED")
                else -> HttpStatusCode.OK to envelope("<u:Ok xmlns:u=\"urn:x\"/>")
            }
        }
        control.load(renderer, pick)
        assertEquals(2, plays)
    }

    @Test
    fun aRendererThatNeverPlaysIsBelievedInTheEnd() = runTest {
        val (control, _) = control {
            when {
                it.action.orEmpty().endsWith("#Play\"") -> HttpStatusCode.InternalServerError to refusedPlay
                it.action.orEmpty().endsWith("#GetTransportInfo\"") -> HttpStatusCode.OK to state("STOPPED")
                else -> HttpStatusCode.OK to envelope("<u:Ok xmlns:u=\"urn:x\"/>")
            }
        }
        val refused = assertFailsWith<SoapFault> { control.load(renderer, pick) }
        assertEquals(401, refused.code)
        assertEquals("Play: Invalid Action", refused.message)
    }
}
