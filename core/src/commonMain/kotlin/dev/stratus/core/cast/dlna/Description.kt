package dev.stratus.core.cast.dlna

import dev.stratus.core.xml.eachChild
import dev.stratus.core.xml.skipElement
import dev.stratus.core.xml.textOfElement
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * One of a renderer's services: its type as the renderer declared it, version
 * and all, and where to post to it. The type is sent back exactly as given,
 * since a renderer matches an action against the service it declared and a
 * version it did not is an action it does not have.
 */
data class Service(val type: String, val control: String)

/** A renderer, as its description says it can be driven. */
data class Renderer(
    /** Its UDN, which is the same whichever address it answered from. */
    val id: String,
    val name: String,
    /** Where to send `SetAVTransportURI` and the rest; every renderer has one. */
    val avTransport: Service,
    /** Where to set the volume, and null for one that does not say. */
    val renderingControl: Service?,
    /** Where to ask what it plays, and null for one that does not say. */
    val connectionManager: Service?,
    /** Its own picture of itself, and null for one that has none worth drawing. */
    val icon: String? = null,
)

/** One entry of a description's `iconList`. */
internal data class Icon(val type: String, val width: Int, val url: String)

/**
 * The icon to draw in a list of screens: a PNG or a JPEG -- what both
 * platforms decode without help -- as near as there is to [WANTED] pixels,
 * since the list draws it small on a dense screen. Larger beats smaller at
 * the same distance, because shrinking looks better than stretching.
 */
internal fun bestIcon(icons: List<Icon>): Icon? =
    icons.filter { it.type.lowercase() in DRAWABLE && it.width > 0 }
        .minWithOrNull(compareBy<Icon> { kotlin.math.abs(it.width - WANTED) }.thenByDescending { it.width })

private const val WANTED = 96
private val DRAWABLE = setOf("image/png", "image/jpeg", "image/jpg")

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
            avTransport = device.controls.getValue(AV_TRANSPORT).resolved(base),
            renderingControl = device.controls[RENDERING_CONTROL]?.resolved(base),
            connectionManager = device.controls[CONNECTION_MANAGER]?.resolved(base),
            icon = bestIcon(device.icons)?.let { URLBuilder(base).takeFrom(it.url).buildString() },
        )
    }

    /** Services by their type without the version, which is how they are looked up. */
    private class Device(val name: String, val udn: String, val controls: Map<String, Service>, val icons: List<Icon>)

    /** One device, appended after the ones embedded in it so the root comes first. */
    private fun XmlReader.parseDevice(into: MutableList<Device>) {
        var name = ""
        var udn = ""
        val controls = mutableMapOf<String, Service>()
        val embedded = mutableListOf<Device>()
        val icons = mutableListOf<Icon>()
        eachChild { ns, element ->
            when {
                ns != DEVICE -> skipElement()
                element == "friendlyName" -> name = textOfElement().trim()
                element == "UDN" -> udn = textOfElement().trim()
                element == "serviceList" -> eachChild { _, _ -> parseService()?.let { controls[it.type.substringBeforeLast(':')] = it } }
                element == "iconList" -> eachChild { _, _ -> parseIcon()?.let(icons::add) }
                element == "deviceList" -> eachChild { innerNs, inner ->
                    if (innerNs == DEVICE && inner == "device") parseDevice(embedded) else skipElement()
                }
                else -> skipElement()
            }
        }
        into += Device(name, udn, controls, icons)
        into += embedded
    }

    /** A service's type and its control URL, as written. */
    private fun XmlReader.parseService(): Service? {
        var type: String? = null
        var control: String? = null
        eachChild { ns, element ->
            when {
                ns == DEVICE && element == "serviceType" -> type = textOfElement().trim()
                ns == DEVICE && element == "controlURL" -> control = textOfElement().trim()
                else -> skipElement()
            }
        }
        return Service(type ?: return null, control ?: return null)
    }

    private fun XmlReader.parseIcon(): Icon? {
        var type = ""
        var width = 0
        var url: String? = null
        eachChild { ns, element ->
            when {
                ns == DEVICE && element == "mimetype" -> type = textOfElement().trim()
                ns == DEVICE && element == "width" -> width = textOfElement().trim().toIntOrNull() ?: 0
                ns == DEVICE && element == "url" -> url = textOfElement().trim()
                else -> skipElement()
            }
        }
        return Icon(type, width, url ?: return null)
    }

    private fun Service.resolved(base: String) = copy(control = URLBuilder(base).takeFrom(control).buildString())
}

internal const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport"
internal const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl"
internal const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager"
