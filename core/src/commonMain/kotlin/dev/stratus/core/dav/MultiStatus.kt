package dev.stratus.core.dav

import io.ktor.http.Url
import io.ktor.http.fromHttpToGmtDate
import dev.stratus.core.xml.eachChild
import dev.stratus.core.xml.skipElement
import dev.stratus.core.xml.textOfElement
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Reads a `multistatus` body into resources.
 *
 * Written against what a real server sends rather than against the grammar in
 * the RFC, and the difference shows in three places. Namespaces are matched by
 * URI and never by prefix, because a server may redeclare `DAV:` on every
 * single element -- ours does. Properties are taken only from a `propstat`
 * whose status is a 2xx, since a server reports the ones it could not supply in
 * a second block with a 404. And an `href` arrives doubly encoded: XML escaping
 * over percent-encoding, so `a&b "c".txt` comes back as `a&amp;b%20%22c%22.txt`
 * and has to be undone in that order or the name is wrong.
 */
internal object MultiStatus {
    private const val DAV = "DAV:"
    private const val STRATUS = "https://github.com/C0piIot/stratus"

    fun parse(xml: String, basePath: String): List<DavResource> =
        entries(xml, basePath).filter { it.status == null }.map { it.resource }

    /**
     * A search answer, which is the same multistatus with one thing extra in
     * it: a response carrying a status of its own rather than propstats is
     * about the collection and not about a file. RFC 5323 2.3.1 is where a
     * server says it stopped early, with 507 on the arbiter's own href, and
     * leaving that out would be telling somebody they had seen everything.
     */
    fun parseSearch(xml: String, basePath: String): SearchAnswer {
        val entries = entries(xml, basePath)
        return SearchAnswer(
            found = entries.filter { it.status == null }.map { it.resource },
            cut = entries.any { it.status?.contains("507") == true },
        )
    }

    private fun entries(xml: String, basePath: String): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            val reader = xmlStreaming.newReader(xml)
            while (reader.hasNext()) {
                if (reader.next() == EventType.START_ELEMENT && reader.isDav("response")) {
                    reader.parseResponse(basePath)?.let(out::add)
                }
            }
        } catch (e: DavError) {
            throw e
        } catch (e: Exception) {
            throw DavError.Malformed(e.message ?: "invalid XML")
        }
        return out
    }

    /** One `response`: a resource, and the status if it carried one of its own. */
    private class Entry(val resource: DavResource, val status: String?)

    private fun XmlReader.parseResponse(basePath: String): Entry? {
        var href: String? = null
        var status: String? = null
        var kept = Props()
        eachChild { ns, name ->
            when {
                ns == DAV && name == "href" -> href = textOfElement()
                ns == DAV && name == "status" -> status = textOfElement()
                ns == DAV && name == "propstat" -> {
                    val (props, usable) = parsePropstat()
                    if (usable) kept = kept.merge(props)
                }
                else -> skipElement()
            }
        }
        val location = href ?: return null
        return Entry(
            DavResource(
                path = pathOf(location, basePath),
                isDirectory = kept.isDirectory ?: false,
                size = kept.size,
                contentType = kept.contentType,
                etag = kept.etag,
                lastModifiedEpochMs = kept.lastModified,
                source = kept.source?.let { pathOf(it, basePath) },
            ),
            status,
        )
    }

    private fun XmlReader.parsePropstat(): Pair<Props, Boolean> {
        var props = Props()
        var usable = false
        eachChild { ns, name ->
            when {
                ns == DAV && name == "prop" -> props = parseProp()
                ns == DAV && name == "status" -> usable = isSuccessStatusLine(textOfElement())
                else -> skipElement()
            }
        }
        return props to usable
    }

    /**
     * The properties this parser reads, and therefore the only ones worth asking
     * a server for.
     *
     * [propfindBody] is built from this list, so a property added to the `when`
     * below and not added here would be asked of nobody and never arrive.
     */
    private val READS = listOf(
        "resourcetype",
        "getcontentlength",
        "getcontenttype",
        "getetag",
        "getlastmodified",
    )

    /**
     * A `PROPFIND` body naming what we read, rather than `allprop`.
     *
     * This used to be `allprop` on the grounds that a server might know
     * properties we do not ask about yet and the round trip costs the same. The
     * round trip does; the server's work does not. Measured against
     * stratus-backend on a folder of ten thousand: `allprop` is 6.70 MB and
     * 1.43 s, and these five are 4.11 MB and **0.18 s** -- because answering
     * `supportedlock` and `creationdate` for ten thousand entries is real work
     * done for a client that throws all of it away.
     */
    fun propfindBody(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<propfind xmlns=\"DAV:\"><prop>")
        appendReads()
        append("</prop></propfind>")
    }

    /**
     * [READS], and the one property of Stratus's own this reads. Asking any
     * other server for it costs a 404 in the answer's second propstat.
     */
    private fun StringBuilder.appendReads() {
        for (name in READS) append("<").append(name).append("/>")
        append("<source xmlns=\"").append(STRATUS).append("\"/>")
    }

    /**
     * A `DAV:basicsearch` over [scope], asking for the same properties a
     * listing does -- a result row is drawn the way a listing's row is, so
     * asking for less would mean two kinds of row.
     *
     * `DAV:contains` is the only operator in it. Its meaning is the server's
     * by RFC 5323 5.16, which is what makes it the honest one to send: a
     * client cannot promise what matching it will get, and `DAV:like` would
     * be claiming substrings that the server may not have.
     */
    fun searchBody(scope: String, text: String, limit: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<searchrequest xmlns=\"DAV:\"><basicsearch>")
        append("<select><prop>")
        appendReads()
        append("</prop></select>")
        append("<from><scope><href>").append(escape(scope)).append("</href>")
        append("<depth>infinity</depth></scope></from>")
        append("<where><contains>").append(escape(text)).append("</contains></where>")
        append("<limit><nresults>").append(limit).append("</nresults></limit>")
        append("</basicsearch></searchrequest>")
    }

    /**
     * What a person typed, made safe to put in an element.
     *
     * Only the three characters that end one. A search term is somebody's
     * words -- an ampersand in a filename is ordinary -- and a body that was
     * not valid XML would come back as a 400 nobody could explain.
     */
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun XmlReader.parseProp(): Props {
        var props = Props()
        eachChild { ns, name ->
            if (ns == STRATUS && name == "source") {
                props = props.copy(source = readHref())
                return@eachChild
            }
            if (ns != DAV) {
                skipElement()
                return@eachChild
            }
            props = when (name) {
                "resourcetype" -> props.copy(isDirectory = readResourceType())
                "getcontentlength" -> props.copy(size = textOfElement().trim().toLongOrNull())
                "getcontenttype" -> props.copy(contentType = textOfElement().trim().ifEmpty { null })
                // The quotes are part of the wire format, not of the value, and
                // a weak validator carries a W/ in front of them.
                "getetag" -> props.copy(
                    etag = textOfElement().trim().removePrefix("W/").trim('"').ifEmpty { null },
                )
                "getlastmodified" -> props.copy(lastModified = httpDateToEpochMs(textOfElement().trim()))
                else -> {
                    skipElement()
                    props
                }
            }
        }
        return props
    }

    /** The `DAV:href` inside the current element, if there is one. */
    private fun XmlReader.readHref(): String? {
        var href: String? = null
        eachChild { ns, name ->
            if (ns == DAV && name == "href") href = textOfElement().trim().ifEmpty { null } else skipElement()
        }
        return href
    }

    /** A resource is a collection when `resourcetype` has a `collection` in it. */
    private fun XmlReader.readResourceType(): Boolean {
        var collection = false
        var depth = 1
        while (depth > 0 && hasNext()) {
            when (next()) {
                EventType.START_ELEMENT -> {
                    if (isDav("collection")) collection = true
                    depth++
                }
                EventType.END_ELEMENT -> depth--
                else -> {}
            }
        }
        return collection
    }

    private fun pathOf(href: String, basePath: String): String {
        val encoded = if (href.startsWith("http://") || href.startsWith("https://")) {
            Url(href).encodedPath
        } else {
            href
        }
        val decoded = DavPath.decode(encoded)
        val base = DavPath.decode(basePath)
        val relative = if (base.isNotEmpty() && decoded.startsWith(base)) decoded.removePrefix(base) else decoded
        val absolute = if (relative.startsWith("/")) relative else "/$relative"

        // One form for one resource. A collection's href ends in a slash on most
        // servers -- Apache, sabre/dav, Nextcloud, RFC 4918's own examples -- and
        // not on others, and a path is used as a key: by the backup cache, by the
        // self entry a Depth 1 listing has to drop, by a signed link. Deciding it
        // here is the only place it can be decided once.
        return if (absolute.length > 1) absolute.trimEnd('/') else absolute
    }

    /** "HTTP/1.1 200 OK" -- the code is the only part worth reading. */
    private fun isSuccessStatusLine(line: String): Boolean {
        val code = line.trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return false
        return code in 200..299
    }

    private fun httpDateToEpochMs(value: String): Long? =
        try {
            value.fromHttpToGmtDate().timestamp
        } catch (_: Exception) {
            null
        }

    private fun XmlReader.isDav(name: String) = namespaceURI == DAV && localName == name

    private data class Props(
        val isDirectory: Boolean? = null,
        val size: Long? = null,
        val contentType: String? = null,
        val etag: String? = null,
        val lastModified: Long? = null,
        val source: String? = null,
    ) {
        fun merge(other: Props) = Props(
            isDirectory ?: other.isDirectory,
            size ?: other.size,
            contentType ?: other.contentType,
            etag ?: other.etag,
            lastModified ?: other.lastModified,
            source ?: other.source,
        )
    }
}
