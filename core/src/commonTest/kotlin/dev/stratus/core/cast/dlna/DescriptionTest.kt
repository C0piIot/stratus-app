package dev.stratus.core.cast.dlna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DescriptionTest {

    /** gmrender-resurrect's own, which is the renderer the conformance suite runs. */
    private val gmrender = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living room</friendlyName>
            <UDN>uuid:5a1c0d6e-0000-0000-0000-b827eb000001</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
                <SCPDURL>/upnp/renderconnmgrSCPD.xml</SCPDURL>
                <controlURL>/upnp/control/renderconnmgr1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/upnp/control/rendertransport1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/rendercontrol1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun controlUrlsAreRelativeToWhereTheDescriptionCameFrom() {
        assertEquals(
            Renderer(
                id = "uuid:5a1c0d6e-0000-0000-0000-b827eb000001",
                name = "Living room",
                avTransport = Service("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.40:49152/upnp/control/rendertransport1"),
                renderingControl = Service("urn:schemas-upnp-org:service:RenderingControl:1", "http://192.168.1.40:49152/upnp/control/rendercontrol1"),
                connectionManager = Service("urn:schemas-upnp-org:service:ConnectionManager:1", "http://192.168.1.40:49152/upnp/control/renderconnmgr1"),
            ),
            Description.parse(gmrender, "http://192.168.1.40:49152/description.xml"),
        )
    }

    @Test
    fun aUrlBaseWinsOverTheLocation() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://192.168.1.41:9197/</URLBase>
              <device>
                <friendlyName>[TV] Samsung</friendlyName>
                <UDN>uuid:tv</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>upnp/control/AVTransport1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()
        val renderer = Description.parse(xml, "http://192.168.1.41:7676/smp_2_")
        assertEquals("http://192.168.1.41:9197/upnp/control/AVTransport1", renderer?.avTransport?.control)
        assertNull(renderer?.renderingControl, "a renderer that does not say has no volume to set")
    }

    @Test
    fun theRendererMayBeEmbeddedInSomethingElse() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                <friendlyName>NAS</friendlyName>
                <UDN>uuid:nas</UDN>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>/cd</controlURL>
                  </service>
                </serviceList>
                <deviceList>
                  <device>
                    <friendlyName>NAS speaker</friendlyName>
                    <UDN>uuid:speaker</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
                        <controlURL>/av</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent()
        val renderer = Description.parse(xml, "http://nas:8200/desc.xml")
        assertEquals("NAS speaker", renderer?.name)
        // Its own version, which is what it will match an action against.
        assertEquals(Service("urn:schemas-upnp-org:service:AVTransport:2", "http://nas:8200/av"), renderer?.avTransport)
    }

    @Test
    fun whatHasNoTransportIsNoRenderer() {
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>Router</friendlyName><UDN>uuid:r</UDN></device></root>"""
        assertNull(Description.parse(xml, "http://r/"))
        assertNull(Description.parse("not xml at all <", "http://r/"))
    }
}
