package dev.stratus.core.cast.relay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayRoutesTest {

    private val link = "https://s.example/files/f.mp4?k=k1.x.y"

    @Test
    fun localMeansThisNetwork() {
        for (local in listOf("192.168.1.40", "10.0.0.5", "172.20.1.162", "172.16.0.1", "127.0.0.1", "169.254.3.4", "::ffff:192.168.1.40", "::1", "fe80::1%wlan0", "fd12:3456::1", "[fe80::abcd]")) {
            assertTrue(isLocalAddress(local), local)
        }
        for (remote in listOf("8.8.8.8", "172.32.0.1", "172.15.255.255", "192.169.0.1", "2001:db8::1", "::", "stratus.example", "", "300.1.1.1", "1.2.3")) {
            assertFalse(isLocalAddress(remote), remote)
        }
    }

    @Test
    fun theRouteAnswersItsRendererAndNobodyElse() {
        val routes = RelayRoutes(Random(1))
        val token = routes.open(link, "172.20.1.162")
        assertEquals(32, token.length)

        assertEquals(link, routes.resolve(token, "", "172.20.1.162"))
        assertEquals(link, routes.resolve(token, "", "::ffff:172.20.1.162"), "the same address, as a dual-stack socket reports it")
        assertNull(routes.resolve(token, "", "172.20.1.99"), "another device on the same wifi")
        assertNull(routes.resolve(token, "", "8.8.8.8"), "the internet")
        assertNull(routes.resolve("0".repeat(32), "", "172.20.1.162"), "a token that was never handed out")
    }

    @Test
    fun aRendererOnTheInternetIsNeverServed() {
        // A renderer cannot be on the internet; one claiming to be is not served even by its own address.
        val routes = RelayRoutes()
        val token = routes.open(link, "8.8.8.8")
        assertNull(routes.resolve(token, "", "8.8.8.8"))
    }

    @Test
    fun aPlaylistsSegmentsGoUpstreamWithTheirOwnQuery() {
        val routes = RelayRoutes()
        val token = routes.open("$link&hls=index.m3u8", "192.168.1.40")
        assertEquals(
            "https://s.example/files/f.mp4?hls=copy-3.ts&k=k1.x.y",
            routes.resolve(token, "hls=copy-3.ts&k=k1.x.y", "192.168.1.40"),
        )
    }

    @Test
    fun aClosedOrReplacedRouteAnswersNothing() {
        val routes = RelayRoutes()
        val first = routes.open(link, "192.168.1.40")
        val second = routes.open(link, "192.168.1.40")
        assertNull(routes.resolve(first, "", "192.168.1.40"), "one cast at a time")
        routes.close()
        assertNull(routes.resolve(second, "", "192.168.1.40"))
    }
}
