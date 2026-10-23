package dev.stratus.core.cast.dlna

import dev.stratus.core.cast.CastItem
import dev.stratus.core.cast.CastOutcome
import dev.stratus.core.cast.HLS_TYPE
import dev.stratus.core.cast.Playback
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
import kotlin.test.assertNull
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
                        "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>" +
                        "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>" +
                        "</serviceList></device></root>"
                    else -> {
                        val action = request.headers["SOAPACTION"].orEmpty().substringAfter('#').trimEnd('"')
                        // Asking where it is happens every second, so it is not an action worth listing.
                        if (action !in setOf("GetPositionInfo", "GetTransportInfo", "GetVolume")) {
                            actions += action + " " + request.body.toByteArray().decodeToString()
                        }
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:R xmlns:u=\"urn:x\">" +
                            "<Sink>$sink</Sink><RelTime>0:01:05</RelTime><TrackDuration>0:03:40.000</TrackDuration>" +
                            "<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState><CurrentVolume>35</CurrentVolume>" +
                            "</u:R></s:Body></s:Envelope>"
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

    @Test
    fun whereItHasGotToIsAskedEverySecondAndTheControlsReachIt() = runTest {
        val actions = mutableListOf<String>()
        val caster = DlnaCaster({ _, _, heard -> heard(answer) }, renderer("http-get:*:*:*", actions), backgroundScope)
        caster.startDiscovery()
        val tv = caster.devices.first { it.isNotEmpty() }.single()
        caster.stopDiscovery()
        caster.play(tv, film)

        assertEquals(Playback(positionSeconds = 65, durationSeconds = 220, paused = true, volume = 35), caster.playback.first { it != null })

        caster.resume()
        caster.seek(90)
        assertEquals(90, caster.playback.value?.positionSeconds, "the bar would jump back for a second")
        caster.volume(60)
        assertEquals(60, caster.playback.value?.volume)
        caster.pause()
        assertEquals(listOf("Play", "Seek", "SetVolume", "Pause"), actions.drop(3).map { it.substringBefore(' ') })
        assertTrue("<Target>0:01:30</Target>" in actions[4])

        caster.stop()
        assertEquals(null, caster.playback.value)
    }

    @Test
    fun aRefusedLoadIsSaidWithWhatTheRendererSaid() = runTest {
        val refusing = DlnaControl(
            HttpClient(
                MockEngine { request ->
                    val body = if (request.url.encodedPath == "/desc.xml") {
                        "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>Living room</friendlyName><UDN>uuid:tv</UDN><serviceList>" +
                            "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service>" +
                            "</serviceList></device></root>"
                    } else {
                        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultstring>UPnPError</faultstring><detail>" +
                            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>714</errorCode>" +
                            "<errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
                    }
                    respond(body, if (request.url.encodedPath == "/desc.xml") HttpStatusCode.OK else HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "text/xml"))
                },
            ),
        )
        val caster = DlnaCaster({ _, _, heard -> heard(answer) }, refusing, backgroundScope)
        caster.startDiscovery()
        val tv = caster.devices.first { it.isNotEmpty() }.single()
        caster.stopDiscovery()
        assertEquals(CastOutcome.Failed("error 714: SetAVTransportURI: Illegal MIME-type"), caster.play(tv, film))
        assertEquals(null, caster.playback.value)
    }

    @Test
    fun anIconIsFetchedOnceAndAFailureIsNotAskedAgain() = runTest {
        var asked = 0
        val control = DlnaControl(
            HttpClient(
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/desc.xml" -> respond(
                            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><friendlyName>Box</friendlyName><UDN>uuid:box</UDN>" +
                                "<iconList><icon><mimetype>image/png</mimetype><width>96</width><url>/i.png</url></icon></iconList><serviceList>" +
                                "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service>" +
                                "</serviceList></device></root>",
                            HttpStatusCode.OK,
                        )
                        else -> {
                            asked++
                            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                        }
                    }
                },
            ),
        )
        val caster = DlnaCaster({ _, _, heard -> heard(answer) }, control, backgroundScope)
        caster.startDiscovery()
        val box = caster.devices.first { it.isNotEmpty() }.single()
        caster.stopDiscovery()
        assertEquals("http://tv:1/i.png", box.icon)

        assertEquals(listOf<Byte>(1, 2, 3), caster.icon(box)?.toList())
        caster.icon(box)
        assertEquals(1, asked, "the list is redrawn every cast, and the picture does not change in between")

        val broken = box.copy(icon = "http://tv:1/gone.png")
        val failing = DlnaCaster({ _, _, heard -> heard(answer) }, DlnaControl(HttpClient(MockEngine { asked++; respond("", HttpStatusCode.NotFound) })), backgroundScope)
        asked = 0
        assertNull(failing.icon(broken))
        assertNull(failing.icon(broken))
        assertEquals(1, asked)
    }
}
