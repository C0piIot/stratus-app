package dev.stratus.app.e2e

import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import org.junit.Test
import java.io.FileInputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The library as a storage location, driven the way the system drives it
 * (stratus-app#104).
 *
 * **Through the resolver and not through DocumentsUI.** An instrumentation
 * test runs in the app's own process, so it is the provider's own uid and the
 * `MANAGE_DOCUMENTS` check does not apply to it -- which is the only way to
 * reach this at all, since that permission is signature-level and no test can
 * hold it. What it costs is that nothing here presses a button in the Files
 * app; what it buys is every call the picker makes, against a real server,
 * without automating somebody else's UI on an image that may not even carry
 * it.
 */
class DocumentsTest : E2E() {

    private val resolver = Phone.context.contentResolver
    private val authority = Phone.context.packageName + ".documents"

    /** Signed in, and the document id of the server's own root. */
    private fun rootDocument(): String {
        launch()
        signIn()
        see("Servers")
        resolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)!!.use {
            assertTrue(it.moveToFirst(), "the provider offered no roots after signing in")
            assertEquals("Stratus", it.string(Root.COLUMN_TITLE))
            val flags = it.getInt(it.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_SUPPORTS_IS_CHILD != 0, "a folder here could not be granted as a tree")
            return it.string(Root.COLUMN_DOCUMENT_ID)
        }
    }

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUri(authority, id)

    /**
     * A folder's rows, waited for.
     *
     * The first query always comes back saying it is still loading, because a
     * listing is a round trip and a picker that waited would look frozen. The
     * system queries again when it is told; this does the same by hand.
     */
    private fun children(documentId: String, timeoutMs: Long = 20_000): Map<String, String> {
        val uri = DocumentsContract.buildChildDocumentsUri(authority, documentId)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            resolver.query(uri, null, null, null, null)!!.use { cursor ->
                cursor.extras.getString(DocumentsContract.EXTRA_ERROR)?.let {
                    throw AssertionError("the provider said: $it")
                }
                if (!cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    val rows = mutableMapOf<String, String>()
                    while (cursor.moveToNext()) {
                        rows[cursor.string(Document.COLUMN_DISPLAY_NAME)] = cursor.string(Document.COLUMN_DOCUMENT_ID)
                    }
                    return rows
                }
            }
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("$documentId was still loading")
            Thread.sleep(200)
        }
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    @Test
    fun theServersCollectionsAreWhatTheRootOpensOnto() {
        Stratus.folder("/$unique/")
        val root = rootDocument()

        val top = children(root)
        assertContains(top.keys, "files")
        assertContains(top.keys, "photos")
        assertContains(top.keys, "playlists")

        assertContains(children(top.getValue("files")).keys, unique)
    }

    @Test
    fun creatingAFolderAndAFileReachesTheServer() {
        Stratus.folder("/$unique/")
        val root = rootDocument()
        val here = children(root).getValue("files").let { children(it).getValue(unique) }

        val folder = DocumentsContract.createDocument(resolver, docUri(here), MIME_DIRECTORY, "made")!!
        assertTrue(Stratus.exists("/$unique/made/"), "the folder never reached the server")

        val file = DocumentsContract.createDocument(resolver, folder, "text/plain", "note.txt")!!
        resolver.openOutputStream(file)!!.use { it.write("written from a picker".encodeToByteArray()) }

        assertEquals(
            "written from a picker",
            Stratus.read("/$unique/made/note.txt"),
            "the bytes a picker wrote are not what the server holds",
        )
    }

    @Test
    fun renamingAndDeletingReachTheServer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/before.txt")
        val root = rootDocument()
        val here = children(root).getValue("files").let { children(it).getValue(unique) }

        val before = children(here).getValue("before.txt")
        val after = DocumentsContract.renameDocument(resolver, docUri(before), "after.txt")!!
        assertTrue(Stratus.exists("/$unique/after.txt"))
        assertFalse(Stratus.exists("/$unique/before.txt"))

        assertTrue(DocumentsContract.deleteDocument(resolver, after))
        assertFalse(Stratus.exists("/$unique/after.txt"))
    }

    @Test
    fun aFileReadsBackTheBytesItHasOnTheServer() {
        val body = "a picker can read this"
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/read.txt", body.encodeToByteArray())
        val root = rootDocument()
        val here = children(root).getValue("files").let { children(it).getValue(unique) }

        val id = children(here).getValue("read.txt")
        val read = resolver.openInputStream(docUri(id))!!.use { it.readBytes().decodeToString() }
        assertEquals(body, read)
    }

    /**
     * The whole argument for the ranged reader, measured rather than asserted:
     * reading the end of a large file must not drag the rest of it across.
     * That is the difference between playing a film out of a picker and
     * downloading one.
     */
    @Test
    fun readingTheEndOfALargeFileDoesNotFetchAllOfIt() {
        val size = 4 * 1024 * 1024
        val bytes = ByteArray(size) { (it % 251).toByte() }
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/$unique.bin", bytes)
        val root = rootDocument()
        val here = children(root).getValue("files").let { children(it).getValue(unique) }
        val id = children(here).getValue("$unique.bin")

        link.received.set(0)
        val tail = ByteArray(1024)
        resolver.openFileDescriptor(docUri(id), "r")!!.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                stream.channel.position(size - 1024L)
                var got = 0
                while (got < tail.size) {
                    val n = stream.read(tail, got, tail.size - got)
                    if (n < 0) break
                    got += n
                }
                assertEquals(tail.size, got)
            }
        }

        assertContentEqualsTail(bytes, tail)
        val fetched = link.received.get()
        assertTrue(
            fetched < size / 4,
            "$fetched bytes came back for the last kilobyte of a ${size}-byte file: it downloaded the lot",
        )
    }

    private fun assertContentEqualsTail(whole: ByteArray, tail: ByteArray) {
        val expected = whole.copyOfRange(whole.size - tail.size, whole.size)
        assertTrue(expected.contentEquals(tail), "the bytes at the end of the file are not the ones that came back")
    }

    private companion object {
        const val MIME_DIRECTORY = "vnd.android.document/directory"
    }
}
