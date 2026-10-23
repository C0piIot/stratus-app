package dev.stratus.core.cast.relay

import kotlin.random.Random

/**
 * A screen's way in to something it cannot fetch for itself (stratus-app#153).
 *
 * A renderer is handed a signed link and fetches it, like a Chromecast -- and
 * a renderer old enough has no TLS 1.2 and no root that vouches for Let's
 * Encrypt, so an `https` link is one it accepts and never opens. What it can
 * open is plain http on its own network, so the phone serves the link there:
 * one URL, for one renderer, for as long as something is cast.
 *
 * This is the proxy the Chromecast design was proud of not needing, and it is
 * here only for the renderers that leave no other way: a link the renderer can
 * fetch itself never goes through it.
 */
interface Relay {
    /**
     * A URL on this network that serves [upstream] to the device at
     * [renderer] and to nothing else, or null when the phone has no local
     * address to serve it from. Replaces whatever was published before.
     */
    suspend fun publish(upstream: String, renderer: String): String?

    /** Stops serving what was published, and the listener with it. */
    suspend fun withdraw()
}

/**
 * What a relay serves, decided away from any socket so a test can reach it:
 * one route at a time, since one thing is cast at a time.
 */
class RelayRoutes(private val random: Random = Random.Default) {
    private class Route(val token: String, val upstream: String, val renderer: String)

    private var route: Route? = null

    /** Opens the route for [upstream], to [renderer] alone, and answers its token. */
    fun open(upstream: String, renderer: String): String {
        val token = buildString { repeat(TOKEN_BYTES) { append(random.nextInt(256).toString(16).padStart(2, '0')) } }
        route = Route(token, upstream, renderer)
        return token
    }

    fun close() {
        route = null
    }

    /**
     * Where a request for `/<token>?<query>` from [remote] is fetched from, or
     * null when it is not to be answered at all.
     *
     * Two checks, both on the address the connection came from and both before
     * anything is read from it: it is on this network, and it is the renderer
     * the link was handed to. A token is a secret the renderer was given, but
     * it travels in the clear on the wifi, so it is not the only key.
     *
     * The query is what lets a playlist through: its segments are written as
     * `?hls=…` relative to it, which arrives here as this path with a query,
     * and goes upstream as the link's own path with that query instead.
     */
    fun resolve(token: String, query: String, remote: String): String? {
        val open = route ?: return null
        if (token != open.token) return null
        if (!isLocalAddress(remote) || bare(remote) != bare(open.renderer)) return null
        return if (query.isEmpty()) open.upstream else open.upstream.substringBefore('?') + "?" + query
    }

    private fun bare(address: String) = address.removePrefix("[").removeSuffix("]").removePrefix("::ffff:").lowercase()

    private companion object {
        const val TOKEN_BYTES = 16
    }
}

/**
 * Whether [address] -- an IP as text, v4 or v6 -- is on a local network
 * rather than the internet: loopback, link-local, and the private ranges.
 * Anything that does not parse as an address is not.
 */
fun isLocalAddress(address: String): Boolean {
    val text = address.removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
    val v4 = text.removePrefix("::ffff:")
    if ('.' in v4) {
        val parts = v4.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    if (':' !in text) return false
    if (text == "::1") return true
    val first = text.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
    // fe80::/10 is link-local and fc00::/7 the unique local range.
    return first in 0xfe80..0xfebf || first in 0xfc00..0xfdff
}
