package dev.stratus.core.documents

/**
 * Which file, as one string a picker can hold.
 *
 * Android's document ids are opaque strings it stores, hands to other apps and
 * gives back later, so the only requirement is that one string names one thing
 * for ever. There is one server (stratus-app#131), so a document is a path --
 * but a path alone cannot be an id, because the root of the server is the empty
 * string and an id has to be something.
 *
 * So **an id is the path with a leading slash**, and the root is `"/"`. That
 * needs no sentinel and so cannot collide with a file somebody actually called
 * `root`, and parsing one is the same trim every path in this app goes through.
 *
 * [path] is held without a trailing slash, the one form every path in this app
 * has (see `MultiStatus`), and the root is the empty string.
 */
data class DocumentRef(val path: String) {

    /** What goes in `COLUMN_DOCUMENT_ID`, and never empty. */
    val id: String get() = "/$path"

    /** The WebDAV path, which always has the leading slash. */
    val davPath: String get() = "/$path"

    val name: String get() = path.substringAfterLast('/')

    /** Null at the root, which has nothing above it. */
    fun parent(): DocumentRef? {
        if (path.isEmpty()) return null
        return DocumentRef(path.substringBeforeLast('/', ""))
    }

    fun child(name: String) = DocumentRef(if (path.isEmpty()) name else "$path/$name")

    /**
     * Whether this is somewhere under [ancestor], which is what a tree URI is
     * made of: Android asks before it will let a grant cover a document.
     *
     * The root contains everything, and nothing contains itself -- the picker
     * asks about the root separately.
     */
    fun isUnder(ancestor: DocumentRef): Boolean {
        if (ancestor.path.isEmpty()) return path.isNotEmpty()
        return path.startsWith(ancestor.path + "/")
    }

    companion object {
        /** The root's id, which is what a provider offers as its one document. */
        const val ROOT_ID = "/"

        fun parse(id: String) = DocumentRef(id.trim('/'))

        /** From a WebDAV path, which carries a leading slash. */
        fun of(davPath: String) = DocumentRef(davPath.trim('/'))
    }
}
