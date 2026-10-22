package dev.stratus.core.cast.dlna

import dev.stratus.core.xml.eachChild
import dev.stratus.core.xml.skipElement
import dev.stratus.core.xml.textOfElement
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/** A renderer, as its description says it can be driven. */
data class Renderer(
    /** Its UDN, which is the same whichever address it answered from. */
    val id: String,
    val name: String,
    /** Where to send `SetAVTransportURI` and the rest; every renderer has one. */
    val avTransport: String,
    /** Where to set the volume, and null for one that does not say. */
    val renderingControl: String?,
    /** Where to ask what it plays, and null for one that does not say. */
    val connectionManager: String?,
)

/**
 * Reads a device description into a [Renderer].
 *
 * Two things about real ones decide how. The control URLs are relative, to
 * `URLBase` when the description has one and to where it was fetched from
 * otherwise -- which most of them rely on. And the renderer is not always the
 * root device: a television can describe itself as something else with the
 * renderer embedded in its `deviceList`, so the first device anywhere in the
 * tree that has an AVTransport is the one.
 */
internal object Description {
    private const val DEVICE = "urn:schemas-upnp-org:device-1-0"

    fun parse(xml: String, location: String): Renderer? {
        var base = location
        val devices = mutableListOf<Device>()
        try {
            val reader = xmlStreaming.newReader(xml)
            while (reader.hasNext()) {
                if (reader.next() != EventType.START_ELEMENT || reader.localName != "root") continue
                reader.eachChild { ns, name ->
                    when {
                        ns == DEVICE && name == "URLBase" -> base = reader.textOfElement().trim().ifEmpty { location }
                        ns == DEVICE && name == "device" -> reader.parseDevice(devices)
                        else -> reader.skipElement()
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        val device = devices.firstOrNull { AV_TRANSPORT in it.controls } ?: return null
        return Renderer(
            id = device.udn,
            name = device.name.ifEmpty { device.udn },
            avTransport = resolve(base, device.controls.getValue(AV_TRANSPORT)),
            renderingControl = device.controls[RENDERING_CONTROL]?.let { resolve(base, it) },
            connectionManager = device.controls[CONNECTION_MANAGER]?.let { resolve(base, it) },
        )
    }

    private class Device(val name: String, val udn: String, val controls: Map<String, String>)

    /** One device, appended after the ones embedded in it so the root comes first. */
    private fun XmlReader.parseDevice(into: MutableList<Device>) {
        var name = ""
        var udn = ""
        val controls = mutableMapOf<String, String>()
        val embedded = mutableListOf<Device>()
        eachChild { ns, element ->
            when {
                ns != DEVICE -> skipElement()
                element == "friendlyName" -> name = textOfElement().trim()
                element == "UDN" -> udn = textOfElement().trim()
                element == "serviceList" -> eachChild { _, _ -> parseService()?.let { (type, url) -> controls[type] = url } }
                element == "deviceList" -> eachChild { innerNs, inner ->
                    if (innerNs == DEVICE && inner == "device") parseDevice(embedded) else skipElement()
                }
                else -> skipElement()
            }
        }
        into += Device(name, udn, controls)
        into += embedded
    }

    /** A service's type without its version, and its control URL. */
    private fun XmlReader.parseService(): Pair<String, String>? {
        var type: String? = null
        var control: String? = null
        eachChild { ns, element ->
            when {
                ns == DEVICE && element == "serviceType" -> type = textOfElement().trim()
                ns == DEVICE && element == "controlURL" -> control = textOfElement().trim()
                else -> skipElement()
            }
        }
        return (type ?: return null).substringBeforeLast(':') to (control ?: return null)
    }

    private fun resolve(base: String, url: String): String =
        URLBuilder(base).takeFrom(url).buildString()
}

internal const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport"
internal const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl"
internal const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager"
