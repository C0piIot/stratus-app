package dev.stratus.core.dav

import io.ktor.http.Url
import io.ktor.http.fromHttpToGmtDate
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

    fun parse(xml: String, basePath: String): List<DavResource> {
        val out = mutableListOf<DavResource>()
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

    private fun XmlReader.parseResponse(basePath: String): DavResource? {
        var href: String? = null
        var kept = Props()
        eachChild { ns, name ->
            when {
                ns == DAV && name == "href" -> href = textOfElement()
                ns == DAV && name == "propstat" -> {
                    val (props, usable) = parsePropstat()
                    if (usable) kept = kept.merge(props)
                }
                else -> skipElement()
            }
        }
        val location = href ?: return null
        return DavResource(
            path = pathOf(location, basePath),
            isDirectory = kept.isDirectory ?: false,
            size = kept.size,
            contentType = kept.contentType,
            etag = kept.etag,
            lastModifiedEpochMs = kept.lastModified,
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

    private fun XmlReader.parseProp(): Props {
        var props = Props()
        eachChild { ns, name ->
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

    /**
     * Runs [body] for each element directly inside the current one, then stops
     * on its closing tag. Every branch of [body] must consume its element
     * whole, which is what [skipElement] and [textOfElement] are for -- one that
     * does not leaves the reader inside a child and the rest of the document is
     * read at the wrong depth.
     */
    private inline fun XmlReader.eachChild(body: (ns: String, name: String) -> Unit) {
        while (hasNext()) {
            when (next()) {
                EventType.START_ELEMENT -> body(namespaceURI, localName)
                EventType.END_ELEMENT, EventType.END_DOCUMENT -> return
                else -> {}
            }
        }
    }

    private fun XmlReader.skipElement() {
        var depth = 1
        while (depth > 0 && hasNext()) {
            when (next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                else -> {}
            }
        }
    }

    /** The text of the current element, ignoring any markup nested in it. */
    private fun XmlReader.textOfElement(): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0 && hasNext()) {
            when (next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                EventType.TEXT, EventType.CDSECT, EventType.ENTITY_REF -> if (depth == 1) text.append(this.text)
                else -> {}
            }
        }
        return text.toString()
    }

    private data class Props(
        val isDirectory: Boolean? = null,
        val size: Long? = null,
        val contentType: String? = null,
        val etag: String? = null,
        val lastModified: Long? = null,
    ) {
        fun merge(other: Props) = Props(
            isDirectory ?: other.isDirectory,
            size ?: other.size,
            contentType ?: other.contentType,
            etag ?: other.etag,
            lastModified ?: other.lastModified,
        )
    }
}
