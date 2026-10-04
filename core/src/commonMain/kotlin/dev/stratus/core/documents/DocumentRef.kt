package dev.stratus.core.documents

/**
 * Which file on which server, as one string a picker can hold.
 *
 * Android's document ids are opaque strings it stores, hands to other apps and
 * gives back later, so the only requirement is that one string names one thing
 * for ever. This app has several servers at once, so the server has to be in
 * it: `<instance id>/<path>`.
 *
 * Splitting at the first slash is unambiguous because an instance id is
 * sixteen hexadecimal characters and nothing else (`newInstanceId`), so no
 * separator has to be invented and no escaping is needed. An id is also never
 * handed out twice, which is what makes a document id somebody saved last
 * month either still valid or plainly not.
 *
 * [path] is held without a trailing slash, the one form every path in this app
 * has (see `MultiStatus`), and the root of a server is the empty string.
 */
data class DocumentRef(val instanceId: String, val path: String) {

    /** What goes in `COLUMN_DOCUMENT_ID`. */
    val id: String get() = if (path.isEmpty()) instanceId else "$instanceId/$path"

    /** The WebDAV path, which always has the leading slash. */
    val davPath: String get() = "/$path"

    val name: String get() = path.substringAfterLast('/')

    /** Null at the root of a server, which has nothing above it. */
    fun parent(): DocumentRef? {
        if (path.isEmpty()) return null
        return DocumentRef(instanceId, path.substringBeforeLast('/', ""))
    }

    fun child(name: String) = DocumentRef(instanceId, if (path.isEmpty()) name else "$path/$name")

    /**
     * Whether this is somewhere under [ancestor], which is what a tree URI is
     * made of: Android asks before it will let a grant cover a document.
     *
     * A server's root contains everything on that server, and nothing contains
     * itself -- the picker asks about the root separately.
     */
    fun isUnder(ancestor: DocumentRef): Boolean {
        if (instanceId != ancestor.instanceId) return false
        if (ancestor.path.isEmpty()) return path.isNotEmpty()
        return path.startsWith(ancestor.path + "/")
    }

    companion object {
        fun parse(id: String): DocumentRef {
            val cut = id.indexOf('/')
            if (cut < 0) return DocumentRef(id, "")
            return DocumentRef(id.substring(0, cut), id.substring(cut + 1).trim('/'))
        }

        /** From a WebDAV path, which carries a leading slash and no server. */
        fun of(instanceId: String, davPath: String) = DocumentRef(instanceId, davPath.trim('/'))
    }
}
