package dev.stratus.core.documents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentRefTest {

    @Test
    fun survivesBeingWrittenDownAndReadBack() {
        // Android stores a document id, hands it to another app and gives it
        // back later, so this is the property the whole thing rests on.
        for (ref in listOf(
            DocumentRef(""),
            DocumentRef("files"),
            DocumentRef("files/holiday/a b & c.jpg"),
        )) {
            assertEquals(ref, DocumentRef.parse(ref.id))
        }
    }

    @Test
    fun anIdIsThePathWithItsLeadingSlash() {
        val ref = DocumentRef.parse("/files/a/b.txt")
        assertEquals("files/a/b.txt", ref.path)
        assertEquals("/files/a/b.txt", ref.davPath)
        assertEquals("b.txt", ref.name)
    }

    @Test
    fun theRootIsASlashAndNeverEmpty() {
        // An id has to be something, and the root's path is nothing -- which
        // is the whole reason the leading slash is there (stratus-app#131).
        val root = DocumentRef("")
        assertEquals("/", root.id)
        assertEquals(DocumentRef.ROOT_ID, root.id)
        assertEquals("/", root.davPath)
        assertNull(root.parent())
        assertEquals(root, DocumentRef.parse("/"))
    }

    @Test
    fun aFileCalledRootIsNotTheRoot() {
        // What a sentinel id would have got wrong.
        val file = DocumentRef("root")
        assertEquals("/root", file.id)
        assertEquals(file, DocumentRef.parse(file.id))
    }

    @Test
    fun walksUpAndDownTheTree() {
        val folder = DocumentRef("files/holiday")
        assertEquals(DocumentRef("files/holiday/x.jpg"), folder.child("x.jpg"))
        assertEquals(DocumentRef("files"), folder.parent())
        assertEquals(DocumentRef(""), DocumentRef("files").parent())
    }

    @Test
    fun takesAWebDavPathWithItsLeadingSlash() {
        assertEquals(DocumentRef("files/x"), DocumentRef.of("/files/x"))
        assertEquals(DocumentRef("files"), DocumentRef.of("/files/"))
        assertEquals(DocumentRef(""), DocumentRef.of("/"))
    }

    @Test
    fun aTreeGrantCoversWhatIsUnderItAndNothingElse() {
        val folder = DocumentRef("files/holiday")
        assertTrue(DocumentRef("files/holiday/x.jpg").isUnder(folder))
        assertTrue(DocumentRef("files/holiday/deep/x.jpg").isUnder(folder))
        // A prefix of the name is not a child, or a grant on `holiday` would
        // open `holiday2` -- the same trap the server's share links have.
        assertFalse(DocumentRef("files/holiday2/x.jpg").isUnder(folder))
        assertFalse(folder.isUnder(folder))
        // The root holds everything.
        assertTrue(folder.isUnder(DocumentRef("")))
    }
}
