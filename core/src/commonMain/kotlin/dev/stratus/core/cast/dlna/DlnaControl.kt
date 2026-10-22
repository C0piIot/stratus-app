package dev.stratus.core.cast.dlna

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

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

    /**
     * What [renderer] says it plays. Empty when it has no connection manager
     * or will not answer, which [pickFor] takes as not saying rather than as
     * playing nothing.
     */
    suspend fun protocolInfo(renderer: Renderer): List<ProtocolInfo> {
        val url = renderer.connectionManager ?: return emptyList()
        return try {
            ProtocolInfo.parseList(call(url, CONNECTION_MANAGER, "GetProtocolInfo", emptyList())["Sink"].orEmpty())
        } catch (_: SoapFault) {
            emptyList()
        }
    }

    /** Hands [pick] to [renderer] and starts it. */
    suspend fun load(renderer: Renderer, pick: Pick) {
        val metadata = didl(pick.item.title, pick.item.url, pick.protocolInfo, pick.item.contentType)
        call(
            renderer.avTransport,
            AV_TRANSPORT,
            "SetAVTransportURI",
            listOf(INSTANCE, "CurrentURI" to pick.item.url, "CurrentURIMetaData" to metadata),
        )
        play(renderer)
    }

    suspend fun play(renderer: Renderer) {
        call(renderer.avTransport, AV_TRANSPORT, "Play", listOf(INSTANCE, "Speed" to "1"))
    }

    suspend fun pause(renderer: Renderer) {
        call(renderer.avTransport, AV_TRANSPORT, "Pause", listOf(INSTANCE))
    }

    suspend fun stop(renderer: Renderer) {
        call(renderer.avTransport, AV_TRANSPORT, "Stop", listOf(INSTANCE))
    }

    suspend fun seek(renderer: Renderer, seconds: Long) {
        call(renderer.avTransport, AV_TRANSPORT, "Seek", listOf(INSTANCE, "Unit" to "REL_TIME", "Target" to hms(seconds)))
    }

    /**
     * What the transport is doing as the renderer tells it: `PLAYING`,
     * `STOPPED`, `TRANSITIONING` and the rest of UPnP's words for it.
     */
    suspend fun transportState(renderer: Renderer): String =
        call(renderer.avTransport, AV_TRANSPORT, "GetTransportInfo", listOf(INSTANCE))["CurrentTransportState"].orEmpty()

    /** Sets the volume, 0 to 100; false for a renderer that offers no control of it. */
    suspend fun volume(renderer: Renderer, percent: Int): Boolean {
        val url = renderer.renderingControl ?: return false
        call(url, RENDERING_CONTROL, "SetVolume", listOf(INSTANCE, "Channel" to "Master", "DesiredVolume" to percent.coerceIn(0, 100).toString()))
        return true
    }

    private suspend fun call(url: String, service: String, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val response = http.post(url) {
            header("SOAPACTION", Soap.action(service, action))
            contentType(ContentType.Text.Xml.withParameter("charset", "utf-8"))
            setBody(Soap.body(service, action, args))
        }
        // A fault arrives as a 500 with an envelope in it, which is the
        // answer worth reading; anything else that is not a success is the
        // renderer's HTTP saying no, with nothing more to say.
        val text = response.bodyAsText()
        if (!response.status.isSuccess() && !text.contains("Fault")) {
            throw SoapFault(null, "$action answered ${response.status.value}")
        }
        return Soap.answer(text)
    }

    private companion object {
        /** Every renderer this app drives has one transport, and it is number 0. */
        val INSTANCE = "InstanceID" to "0"
    }
}
