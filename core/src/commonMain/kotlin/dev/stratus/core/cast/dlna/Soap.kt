package dev.stratus.core.cast.dlna

import dev.stratus.core.xml.eachChild
import dev.stratus.core.xml.skipElement
import dev.stratus.core.xml.textOfElement
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/** A renderer saying no, with UPnP's own error code when it gave one. */
class SoapFault(val code: Int?, description: String) : Exception(description)

/**
 * The control half of UPnP, which is SOAP: an envelope per action, posted to
 * the service's control URL with the action named in a header.
 */
internal object Soap {
    private const val ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val CONTROL = "urn:schemas-upnp-org:control-1-0"

    /** The `SOAPACTION` header, quotes included: renderers that need them refuse it without. */
    fun action(service: String, action: String) = "\"$service:1#$action\""

    /**
     * The body for [action] on [service], with the arguments in the order
     * given. Order is part of the contract -- the specification says so and
     * some renderers read them by position.
     */
    fun body(service: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"$ENVELOPE\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body><u:$action xmlns:u=\"$service:1\">")
        for ((name, value) in args) append("<$name>").append(escape(value)).append("</$name>")
        append("</u:$action></s:Body></s:Envelope>")
    }

    /**
     * The out-arguments of an answer, by name, or a [SoapFault] for a
     * renderer that refused. The answer's element is matched by local name
     * alone, because renderers disagree about the namespace they put it in.
     */
    fun answer(xml: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var fault: SoapFault? = null
        try {
            val reader = xmlStreaming.newReader(xml)
            while (reader.hasNext()) {
                if (reader.next() != EventType.START_ELEMENT || reader.namespaceURI != ENVELOPE || reader.localName != "Body") continue
                reader.eachChild { ns, name ->
                    if (ns == ENVELOPE && name == "Fault") {
                        fault = reader.parseFault()
                    } else {
                        reader.eachChild { _, arg -> out[arg] = reader.textOfElement() }
                    }
                }
            }
        } catch (e: Exception) {
            throw SoapFault(null, e.message ?: "invalid XML")
        }
        fault?.let { throw it }
        return out
    }

    private fun XmlReader.parseFault(): SoapFault {
        var code: Int? = null
        var description: String? = null
        var faultString = "the renderer refused"
        eachChild { _, name ->
            when (name) {
                "faultstring" -> faultString = textOfElement()
                "detail" -> eachChild { _, _ ->
                    eachChild { innerNs, inner ->
                        when {
                            innerNs == CONTROL && inner == "errorCode" -> code = textOfElement().trim().toIntOrNull()
                            innerNs == CONTROL && inner == "errorDescription" -> description = textOfElement()
                            else -> skipElement()
                        }
                    }
                }
                else -> skipElement()
            }
        }
        return SoapFault(code, description ?: faultString)
    }
}

/**
 * The one element an item is described with, DIDL-Lite: what it is called,
 * what kind of thing it is and where to fetch it. Renderers that show a title
 * show this one; renderers that need a `protocolInfo` before they will play
 * take it from the `res` element rather than from the URL.
 */
internal fun didl(title: String, url: String, protocolInfo: String, contentType: String): String =
    "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
        "<dc:title>${escape(title)}</dc:title>" +
        "<upnp:class>${upnpClass(contentType)}</upnp:class>" +
        "<res protocolInfo=\"${escape(protocolInfo)}\">${escape(url)}</res>" +
        "</item></DIDL-Lite>"

private fun upnpClass(contentType: String): String = when {
    contentType.startsWith("image/") -> "object.item.imageItem.photo"
    contentType.startsWith("audio/") -> "object.item.audioItem.musicTrack"
    else -> "object.item.videoItem"
}

/**
 * Text made safe for an element or an attribute. All five, because a signed
 * URL is full of ampersands and DIDL travels as the text of an argument, so
 * it is escaped twice -- once here and once again by [Soap.body].
 */
internal fun escape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

/**
 * A position as UPnP reads one -- `H:MM:SS`, sometimes with a fraction --
 * or null for anything else, `NOT_IMPLEMENTED` included.
 */
internal fun seconds(hms: String?): Long? {
    val parts = hms?.trim()?.substringBefore('.')?.split(':') ?: return null
    if (parts.size != 3) return null
    val (h, m, s) = parts.map { it.toLongOrNull() ?: return null }
    return h * 3600 + m * 60 + s
}

/** A position as UPnP writes one, `H:MM:SS`. */
internal fun hms(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val m = (s / 60) % 60
    val sec = s % 60
    return "${s / 3600}:${if (m < 10) "0" else ""}$m:${if (sec < 10) "0" else ""}$sec"
}
