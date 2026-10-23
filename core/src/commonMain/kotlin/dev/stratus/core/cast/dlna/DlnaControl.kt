package dev.stratus.core.cast.dlna

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Driving a renderer: its description, what it plays, and the transport.
 *
 * Plain HTTP on the local network, the same on every platform, which is why it
 * is here rather than beside discovery. The renderer fetches the media itself,
 * so nothing sent here carries a credential: what it is given is a signed link
 * with the signature as its only key, which is the same bargain the Chromecast
 * makes.
 */
class DlnaControl(private val http: HttpClient) {

    /** The renderer at [location], or null when what is there is not one. */
    suspend fun describe(location: String): Renderer? {
        val response = http.get(location)
        if (!response.status.isSuccess()) return null
        return Description.parse(response.bodyAsText(), location)
    }

    /** The bytes at [url], a renderer's icon, or null when it would not hand them over. */
    suspend fun bytes(url: String): ByteArray? = try {
        val response = http.get(url) { header(HttpHeaders.Connection, "close") }
        if (response.status.isSuccess()) response.readRawBytes() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * What [renderer] says it plays. Empty when it has no connection manager
     * or will not answer -- a refusal, or an answer that starts and never
     * finishes, which a real one did (STRATUS-APP-6) -- and [pickFor] takes
     * that as not saying rather than as playing nothing.
     */
    suspend fun protocolInfo(renderer: Renderer): List<ProtocolInfo> {
        val service = renderer.connectionManager ?: return emptyList()
        return try {
            ProtocolInfo.parseList(call(service, "GetProtocolInfo", emptyList())["Sink"].orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Hands [pick] to [renderer] and starts it. */
    suspend fun load(renderer: Renderer, pick: Pick) {
        val metadata = didl(pick.item.title, pick.item.url, pick.protocolInfo, pick.item.contentType)
        call(
            renderer.avTransport,
            "SetAVTransportURI",
            listOf(INSTANCE, "CurrentURI" to pick.item.url, "CurrentURIMetaData" to metadata),
        )
        startAfterLoading(renderer)
    }

    /**
     * Play, after a load, for renderers that do not want one. Plenty start by
     * themselves once they have a URI and refuse a `Play` that arrives while
     * they are still opening it -- with UPnP's 401, Invalid Action, which is
     * the wrong code for it (eHomeMediaCenter, stratus-app#153). So a refusal
     * is checked against what the renderer is doing: already playing, or on
     * its way there, is what was asked for, and anything else is asked again
     * a moment later before the refusal is believed.
     */
    private suspend fun startAfterLoading(renderer: Renderer) {
        var refused: SoapFault? = null
        repeat(PLAY_TRIES) { attempt ->
            if (attempt > 0) delay(PLAY_PAUSE_MS)
            try {
                play(renderer)
                return
            } catch (e: SoapFault) {
                refused = e
            }
            val state = try {
                transportState(renderer)
            } catch (e: SoapFault) {
                ""
            }
            if (state == "PLAYING" || state == "TRANSITIONING") return
        }
        throw refused ?: SoapFault(null, "Play was refused")
    }

    suspend fun play(renderer: Renderer) {
        call(renderer.avTransport, "Play", listOf(INSTANCE, "Speed" to "1"))
    }

    suspend fun pause(renderer: Renderer) {
        call(renderer.avTransport, "Pause", listOf(INSTANCE))
    }

    suspend fun stop(renderer: Renderer) {
        call(renderer.avTransport, "Stop", listOf(INSTANCE))
    }

    suspend fun seek(renderer: Renderer, seconds: Long) {
        call(renderer.avTransport, "Seek", listOf(INSTANCE, "Unit" to "REL_TIME", "Target" to hms(seconds)))
    }

    /**
     * What the transport is doing as the renderer tells it: `PLAYING`,
     * `STOPPED`, `TRANSITIONING` and the rest of UPnP's words for it.
     */
    suspend fun transportState(renderer: Renderer): String =
        call(renderer.avTransport, "GetTransportInfo", listOf(INSTANCE))["CurrentTransportState"].orEmpty()

    /**
     * Where the track is and how long it is, in seconds, each null when the
     * renderer says `NOT_IMPLEMENTED` or nothing -- which plenty do for a
     * stream whose length they have not worked out yet.
     */
    suspend fun position(renderer: Renderer): Pair<Long?, Long?> {
        val answer = call(renderer.avTransport, "GetPositionInfo", listOf(INSTANCE))
        return seconds(answer["RelTime"]) to seconds(answer["TrackDuration"])?.takeIf { it > 0 }
    }

    /** The volume, 0 to 100, or null for a renderer that offers no control of it. */
    suspend fun currentVolume(renderer: Renderer): Int? {
        val service = renderer.renderingControl ?: return null
        return call(service, "GetVolume", listOf(INSTANCE, "Channel" to "Master"))["CurrentVolume"]?.trim()?.toIntOrNull()
    }

    /** Sets the volume, 0 to 100; false for a renderer that offers no control of it. */
    suspend fun volume(renderer: Renderer, percent: Int): Boolean {
        val service = renderer.renderingControl ?: return false
        call(service, "SetVolume", listOf(INSTANCE, "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString()))
        return true
    }

    private suspend fun call(service: Service, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val response = http.post(service.control) {
            header("SOAPACTION", Soap.action(service.type, action))
            // One connection per action. A renderer's HTTP server is often a
            // few hundred lines inside a television, and one that mishandles
            // keep-alive reads the next request as the tail of the last: it
            // hung half way through an answer and then called the action
            // after it invalid (UPnP 401), which a fresh connection avoids
            // for the price of a handshake on the local network.
            header(HttpHeaders.Connection, "close")
            contentType(ContentType.Text.Xml.withParameter("charset", "utf-8"))
            setBody(Soap.body(service.type, action, args))
        }
        // A fault arrives as a 500 with an envelope in it, which is the
        // answer worth reading; anything else that is not a success is the
        // renderer's HTTP saying no, with nothing more to say.
        val text = response.bodyAsText()
        if (!response.status.isSuccess() && !text.contains("Fault")) {
            throw SoapFault(null, "$action answered ${response.status.value}")
        }
        return try {
            Soap.answer(text)
        } catch (e: SoapFault) {
            // Which action it was is the first thing a report needs.
            throw SoapFault(e.code, "$action: ${e.message}")
        }
    }

    private companion object {
        const val PLAY_TRIES = 3
        const val PLAY_PAUSE_MS = 700L

        /** Every renderer this app drives has one transport, and it is number 0. */
        val INSTANCE = "InstanceID" to "0"
    }
}
