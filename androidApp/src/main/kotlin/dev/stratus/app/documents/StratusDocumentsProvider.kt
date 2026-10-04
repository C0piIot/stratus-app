package dev.stratus.app.documents

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Point
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import dev.stratus.app.R
import dev.stratus.core.DOCUMENTS_AUTHORITY_SUFFIX
import dev.stratus.core.appContainer
import dev.stratus.core.documents.DocumentRef
import dev.stratus.core.documents.DocumentRow
import dev.stratus.core.documents.DocumentTree
import dev.stratus.core.documents.Listing
import dev.stratus.core.documents.RangeReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * The library as a storage location in Files and in every picker
 * (stratus-app#104).
 *
 * What makes a provider appear beside Drive and Nextcloud is the
 * `DOCUMENTS_PROVIDER` intent filter in the manifest, and what keeps anybody
 * else from binding to it is `MANAGE_DOCUMENTS`, which only DocumentsUI holds.
 * Nothing here is asked of the server that WebDAV does not already answer, so
 * it works against any WebDAV server.
 *
 * **It is not a mount.** Nothing appears under `/storage` and `java.io.File`
 * never sees it: what is promised is the apps that use the picker, not apps.
 *
 * This class is the shell. Everything with a decision in it -- what a document
 * id is, when a listing is asked for again, which verb each operation is --
 * is `DocumentTree` in common code, where a test reaches it without an
 * emulator. It lives in the application module rather than beside that code
 * because it is the one piece that needs a drawable, and Android resources are
 * not available in the multiplatform library.
 */
class StratusDocumentsProvider : DocumentsProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Never null once the system has created this; it is what creating means. */
    private val app: Context get() = checkNotNull(context) { "the provider has no context" }

    private val authority: String get() = app.packageName + DOCUMENTS_AUTHORITY_SUFFIX

    /**
     * Built on first use rather than in [onCreate]: a provider is created
     * while the app is starting, and this opens the keychain and a database.
     */
    private val tree: DocumentTree by lazy {
        appContainer(app).documents(scope) { ref ->
            app.contentResolver.notifyChange(childrenUri(ref), null)
        }
    }

    /**
     * Where a proxy descriptor's reads are served, which must not be the main
     * thread: `onRead` is synchronous and a read is a network request.
     */
    private val reads: Handler by lazy {
        Handler(HandlerThread("stratus-documents").apply { start() }.looper)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        for (root in runBlocking { tree.roots() }) {
            cursor.newRow()
                .add(Root.COLUMN_ROOT_ID, root.instanceId)
                .add(Root.COLUMN_DOCUMENT_ID, root.document.id)
                .add(Root.COLUMN_TITLE, root.title)
                .add(Root.COLUMN_SUMMARY, root.summary)
                .add(Root.COLUMN_ICON, R.drawable.ic_stratus_root)
                // CREATE so it is offered when another app is saving, and
                // IS_CHILD so a folder here can be granted as a tree.
                .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }
        // Nothing is listed when nobody is signed in, which is the honest
        // answer and not an error: the sidebar simply has no Stratus in it.
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val row = runBlocking { tree.one(DocumentRef.parse(documentId)) }
            ?: throw FileNotFoundException(documentId)
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        cursor.put(row)
        return cursor
    }

    /**
     * A folder's contents, which is a network round trip -- so the cursor goes
     * back at once saying it is still loading, and the resolver is told when
     * the answer arrives. A picker that waited would look frozen.
     */
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val ref = DocumentRef.parse(parentDocumentId)
        val listing = runBlocking { tree.children(ref) }
        val extras = Bundle().apply {
            when (listing) {
                is Listing.Loading -> putBoolean(DocumentsContract.EXTRA_LOADING, true)
                // Said rather than drawn as an empty folder, which is the lie
                // a listing that failed would otherwise tell.
                is Listing.Failed -> putString(DocumentsContract.EXTRA_ERROR, listing.message)
                is Listing.Loaded -> Unit
            }
        }
        val cursor = object : MatrixCursor(projection ?: DOCUMENT_COLUMNS) {
            override fun getExtras(): Bundle = extras
        }
        cursor.setNotificationUri(app.contentResolver, childrenUri(ref))
        if (listing is Listing.Loaded) listing.rows.forEach(cursor::put)
        return cursor
    }

    /** Pull to refresh, and the only thing that makes a listing be asked for twice. */
    override fun refresh(uri: Uri, extras: Bundle?, cancellationSignal: CancellationSignal?): Boolean {
        val id = DocumentsContract.getDocumentId(uri) ?: return false
        runBlocking { tree.refresh(DocumentRef.parse(id)) }
        return true
    }

    /** What a tree grant is built on: Android asks before it lets one cover a document. */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        DocumentRef.parse(documentId).isUnder(DocumentRef.parse(parentDocumentId))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        runBlocking { tree.create(DocumentRef.parse(parentDocumentId), mimeType, displayName) }.id

    override fun renameDocument(documentId: String, displayName: String): String =
        runBlocking { tree.rename(DocumentRef.parse(documentId), displayName) }.id

    override fun deleteDocument(documentId: String) {
        runBlocking { tree.delete(DocumentRef.parse(documentId)) }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val ref = DocumentRef.parse(documentId)
        return if ('w' in mode) openForWriting(ref) else openForReading(ref)
    }

    /**
     * The whole point of the ranged reader: a film plays out of the picker for
     * the part that is watched rather than the whole file.
     *
     * A server that does not say how long a file is leaves nothing to seek
     * within, so that one is fetched to the cache instead -- rare, and better
     * than refusing.
     */
    private fun openForReading(ref: DocumentRef): ParcelFileDescriptor {
        val row = runBlocking { tree.one(ref) } ?: throw FileNotFoundException(ref.id)
        val size = row.size ?: return ParcelFileDescriptor.open(fetched(ref), ParcelFileDescriptor.MODE_READ_ONLY)
        val reader = tree.reader(ref, size, scope)

        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                val bytes = runBlocking { reader.read(offset, size) }
                bytes.copyInto(data)
                bytes.size
            } catch (e: Exception) {
                // The reader's own message never reaches the app on the other
                // end; an errno is the only thing this interface can carry.
                throw ErrnoException("read ${ref.id}: ${e.message}", OsConstants.EIO)
            }

            override fun onRelease() = reader.close()
        }
        val storage = app.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, reads)
    }

    /**
     * Writing is the other way round and has to be: whoever holds the
     * descriptor seeks in it, so the bytes land on this disk first and go out
     * when it is closed. It is what lets any app save into Stratus from its
     * own dialog.
     */
    private fun openForWriting(ref: DocumentRef): ParcelFileDescriptor {
        val staged = File.createTempFile("stratus-out", null, app.cacheDir)
        val type = runBlocking { tree.one(ref) }?.contentType
        return ParcelFileDescriptor.open(
            staged,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
            reads,
        ) {
            try {
                runBlocking { tree.upload(ref, staged.absolutePath, type) }
            } finally {
                staged.delete()
            }
        }
    }

    /**
     * The server's own picture of a file, where it has one.
     *
     * Through a cache file because this interface hands back a descriptor and
     * not bytes. A server that has no such thing answers something that is not
     * an image, [DocumentTree] says so with a null, and the picker draws its
     * own icon -- which is what it would have done anyway.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        val ref = DocumentRef.parse(documentId)
        val bytes = runBlocking { tree.thumbnail(ref, sizeHint.x.coerceAtLeast(MIN_THUMBNAIL)) } ?: return null
        val file = File.createTempFile("stratus-thumb", null, app.cacheDir)
        file.writeBytes(bytes)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        file.delete() // Unlinked and still readable through the descriptor.
        return AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    /** The fallback for a file whose length the server never stated. */
    private fun fetched(ref: DocumentRef): File {
        val file = File.createTempFile("stratus-in", null, app.cacheDir)
        runBlocking { tree.download(ref, file.absolutePath) }
        return file
    }

    private fun childrenUri(ref: DocumentRef): Uri =
        DocumentsContract.buildChildDocumentsUri(authority, ref.id)

    private fun MatrixCursor.put(row: DocumentRow) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, row.ref.id)
            .add(Document.COLUMN_DISPLAY_NAME, row.name)
            .add(Document.COLUMN_MIME_TYPE, row.contentType ?: "application/octet-stream")
            .add(Document.COLUMN_SIZE, row.size)
            .add(Document.COLUMN_LAST_MODIFIED, row.lastModifiedEpochMs)
            .add(Document.COLUMN_FLAGS, flagsFor(row))
    }

    /**
     * What may be done to a row, said optimistically.
     *
     * WebDAV has no way to report who may write where, so the choice is
     * between asking the server before drawing a button -- a round trip per
     * row -- and offering it and reporting the refusal. Every WebDAV client
     * does the second, and against a Stratus it is what makes the generated
     * collections behave: a create under `photos/` fails where the server says
     * so rather than where this app guessed.
     */
    private fun flagsFor(row: DocumentRow): Int {
        // A server's own root has nothing above it, so there is nothing to
        // rename it to and nowhere to delete it from.
        if (row.ref.path.isEmpty()) return Document.FLAG_DIR_SUPPORTS_CREATE

        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        flags = flags or if (row.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            Document.FLAG_SUPPORTS_WRITE
        }
        val type = row.contentType.orEmpty()
        if (type.startsWith("image/") || type.startsWith("video/")) {
            flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL
        }
        return flags
    }

    private companion object {
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )

        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        const val MIN_THUMBNAIL = 256
    }
}
