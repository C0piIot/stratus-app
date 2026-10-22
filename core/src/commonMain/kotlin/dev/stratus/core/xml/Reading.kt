package dev.stratus.core.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader

/**
 * Runs [body] for each element directly inside the current one, then stops
 * on its closing tag. Every branch of [body] must consume its element
 * whole, which is what [skipElement] and [textOfElement] are for -- one that
 * does not leaves the reader inside a child and the rest of the document is
 * read at the wrong depth.
 */
internal inline fun XmlReader.eachChild(body: (ns: String, name: String) -> Unit) {
    while (hasNext()) {
        when (next()) {
            EventType.START_ELEMENT -> body(namespaceURI, localName)
            EventType.END_ELEMENT, EventType.END_DOCUMENT -> return
            else -> {}
        }
    }
}

internal fun XmlReader.skipElement() {
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
internal fun XmlReader.textOfElement(): String {
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
