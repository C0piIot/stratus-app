package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastItem
import dev.stratus.core.cast.CastOutcome
import dev.stratus.core.cast.HLS_TYPE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DlnaCasterTest {

    private val film = CastItem(
        url = "https://s/files/f.mkv?k=t&hls=index.m3u8",
        contentType = HLS_TYPE,
        title = "f.mkv",
        fallback = CastItem("https://s/files/f.mkv?k=t", "video/x-matroska", "f.mkv"),
    )

    private val answer = "HTTP/1.1 200 OK\r\nLOCATION: http://tv:1/desc.xml\r\n" +
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:tv::x\r\n\r\n"

    /** A renderer that plays [sink], and remembers which actions it was sent. */
    private fun renderer(sink: String, actions: MutableList<String>) = DlnaControl(
        HttpClient(
            MockEngine { request ->
                val body = when (request.url.encodedPath) {
                    "/desc.xml" -> "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>Living room</friendlyName><UDN>uuid:tv</UDN><serviceList>" +
                        "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service>" +
                        "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>" +
                        "</serviceList></device></root>"
                    else -> {
                        actions += request.headers["SOAPACTION"].orEmpty().substringAfter('#').trimEnd('"') + " " +
                            request.body.toByteArray().decodeToString()
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:R xmlns:u=\"urn:x\">" +
                            "<Sink>$sink</Sink></u:R></s:Body></s:Envelope>"
                    }
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/xml"))
            },
        ),
    )

    @Test
    fun aRendererThatAnswersTheSearchIsListedByItsOwnName() = runTest {
        val searches = mutableListOf<String>()
        val caster = DlnaCaster({ request, _, heard -> searches += request; heard(answer) }, renderer("", mutableListOf()), backgroundScope)
        caster.startDiscovery()
        val listed = caster.devices.first { it.isNotEmpty() }
        assertEquals("Living room", listed.single().name)
        assertTrue(listed.single().id.startsWith("dlna:"), "an id a Cast route could share")
        assertTrue(searches.first().startsWith("M-SEARCH"))
        caster.stopDiscovery()
    }

    @Test
    fun theRendererIsGivenTheFirstChoiceItPlays() = runTest {
        val actions = mutableListOf<String>()
        val caster = DlnaCaster({ _, _, heard -> heard(answer) }, renderer("http-get:*:video/x-matroska:*", actions), backgroundScope)
        caster.startDiscovery()
        val tv = caster.devices.first { it.isNotEmpty() }.single()

        assertEquals(CastOutcome.Started, caster.play(tv, film))
        assertEquals(listOf("GetProtocolInfo", "SetAVTransportURI", "Play"), actions.map { it.substringBefore(' ') })
        assertTrue("<CurrentURI>https://s/files/f.mkv?k=t</CurrentURI>" in actions[1], "it does not take a playlist, so it gets the file")

        caster.stop()
        assertEquals("Stop", actions.last().substringBefore(' '))
    }

    @Test
    fun aRendererThatPlaysNoneOfItIsSentNothing() = runTest {
        val actions = mutableListOf<String>()
        val caster = DlnaCaster({ _, _, heard -> heard(answer) }, renderer("http-get:*:audio/mpeg:*", actions), backgroundScope)
        caster.startDiscovery()
        val tv = caster.devices.first { it.isNotEmpty() }.single()

        assertEquals(CastOutcome.CannotPlay(listOf(HLS_TYPE, "video/x-matroska")), caster.play(tv, film))
        assertEquals(listOf("GetProtocolInfo"), actions.map { it.substringBefore(' ') })
    }

    @Test
    fun aSearchThatFailsIsTriedAgain() = runTest {
        var tries = 0
        val caster = DlnaCaster(
            { _, _, heard -> if (++tries == 1) error("no wifi") else heard(answer) },
            renderer("", mutableListOf()),
            backgroundScope,
        )
        caster.startDiscovery()
        caster.devices.first { it.isNotEmpty() }
        assertEquals(2, tries)
    }
}
