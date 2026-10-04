package dev.stratus.core.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentRefTest {

    private val server = "0123456789abcdef"

    @Test
    fun survivesBeingWrittenDownAndReadBack() {
        // Android stores a document id, hands it to another app and gives it
        // back later, so this is the property the whole thing rests on.
        for (ref in listOf(
            DocumentRef(server, ""),
            DocumentRef(server, "files"),
            DocumentRef(server, "files/holiday/a b & c.jpg"),
        )) {
            assertEquals(ref, DocumentRef.parse(ref.id))
        }
    }

    @Test
    fun theServerIsTheFirstSegmentAndNeedsNoEscaping() {
        // An instance id is sixteen hexadecimal characters, so the first slash
        // is always the separator however odd the path is.
        val ref = DocumentRef.parse("$server/files/a/b.txt")
        assertEquals(server, ref.instanceId)
        assertEquals("files/a/b.txt", ref.path)
        assertEquals("/files/a/b.txt", ref.davPath)
        assertEquals("b.txt", ref.name)
    }

    @Test
    fun aServersRootIsTheIdOnItsOwn() {
        val root = DocumentRef(server, "")
        assertEquals(server, root.id)
        assertEquals("/", root.davPath)
        assertNull(root.parent())
        assertEquals(root, DocumentRef.parse(server))
    }

    @Test
    fun walksUpAndDownTheTree() {
        val folder = DocumentRef(server, "files/holiday")
        assertEquals(DocumentRef(server, "files/holiday/x.jpg"), folder.child("x.jpg"))
        assertEquals(DocumentRef(server, "files"), folder.parent())
        assertEquals(DocumentRef(server, ""), DocumentRef(server, "files").parent())
    }

    @Test
    fun takesAWebDavPathWithItsLeadingSlash() {
        assertEquals(DocumentRef(server, "files/x"), DocumentRef.of(server, "/files/x"))
        assertEquals(DocumentRef(server, "files"), DocumentRef.of(server, "/files/"))
        assertEquals(DocumentRef(server, ""), DocumentRef.of(server, "/"))
    }

    @Test
    fun aTreeGrantCoversWhatIsUnderItAndNothingElse() {
        val folder = DocumentRef(server, "files/holiday")
        assertTrue(DocumentRef(server, "files/holiday/x.jpg").isUnder(folder))
        assertTrue(DocumentRef(server, "files/holiday/deep/x.jpg").isUnder(folder))
        // A prefix of the name is not a child, or a grant on `holiday` would
        // open `holiday2` -- the same trap the server's share links have.
        assertFalse(DocumentRef(server, "files/holiday2/x.jpg").isUnder(folder))
        assertFalse(folder.isUnder(folder))
        assertFalse(DocumentRef("deadbeefdeadbeef", "files/holiday/x.jpg").isUnder(folder))
        // A server's root holds everything on that server.
        assertTrue(folder.isUnder(DocumentRef(server, "")))
    }
}
