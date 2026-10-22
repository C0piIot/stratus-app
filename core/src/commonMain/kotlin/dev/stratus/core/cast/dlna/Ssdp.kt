package dev.stratus.core.cast.dlna

/**
 * Finding renderers: SSDP, the discovery half of UPnP (stratus-app#153).
 *
 * The text of it and nothing else. Sending a datagram to a multicast group and
 * listening for the answers is the platform's -- a `MulticastLock` on Android,
 * an entitlement Apple grants on request on iOS -- and is a dumb executor like
 * every other platform half here; what is asked and what an answer means are
 * decided in this file, where a test can reach them.
 */
object Ssdp {
    const val HOST = "239.255.255.250"
    const val PORT = 1900

    /** What a television or a speaker that takes a URL calls itself. */
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /**
     * The search, sent to [HOST]:[PORT]. [waitSeconds] is how long a renderer
     * may take to answer -- each one waits a random part of it, so that a
     * houseful of them does not answer in the same millisecond.
     */
    fun search(waitSeconds: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $HOST:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $waitSeconds\r\n" +
            "ST: $MEDIA_RENDERER\r\n" +
            "\r\n"

    /**
     * A renderer out of one datagram, or null when it is not about one.
     *
     * Two kinds arrive on the same socket: the answer to a search, and the
     * `NOTIFY` a renderer multicasts when it joins the network. Both are taken,
     * since a television switched on while somebody is choosing should appear.
     * One that is leaving says `ssdp:byebye` and is not a sighting at all.
     */
    fun sightingOf(datagram: String): Sighting? {
        val lines = datagram.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim() ?: return null
        val answer = first.startsWith("HTTP/1.1 200")
        val notify = first.startsWith("NOTIFY * HTTP/1.1")
        if (!answer && !notify) return null

        // Header names are case-insensitive, and renderers disagree about it:
        // one sends LOCATION, another Location.
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
        }.toMap()

        val kind = (if (answer) headers["ST"] else headers["NT"]) ?: return null
        if (!kind.startsWith(RENDERER_TYPE)) return null
        if (notify && headers["NTS"] != "ssdp:alive") return null
        val location = headers["LOCATION"]?.takeIf { it.isNotEmpty() } ?: return null
        return Sighting(location = location, usn = headers["USN"].orEmpty())
    }

    /** Any version: a :2 renderer answers a :1 search, and says it is a :2. */
    private const val RENDERER_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:"
}

/** A renderer seen on the network, before anybody has asked what it is. */
data class Sighting(
    /** Where its description is, an http URL on the local network. */
    val location: String,
    /** Its own name for itself, which repeats across sightings of one device. */
    val usn: String,
)
