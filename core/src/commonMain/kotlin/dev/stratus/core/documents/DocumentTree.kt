package dev.stratus.core.documents

import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.Connections
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.net.originOf
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** One server, as a picker draws it in its sidebar. */
data class DocumentRoot(
    val instanceId: String,
    val title: String,
    val summary: String,
) {
    val document get() = DocumentRef(instanceId, "")
}

/** One row of a listing. */
data class DocumentRow(
    val ref: DocumentRef,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val contentType: String?,
    val lastModifiedEpochMs: Long?,
    /**
     * What the server says this content is, where it says anything.
     *
     * Android's picker has no use for it; iOS's File Provider does -- an
     * item carries a version and that is the only honest one WebDAV offers
     * (stratus-app#105).
     */
    val etag: String? = null,
)

/** What is known about a folder at this moment, which is not always its contents. */
sealed interface Listing {
    /** Asked for and not back yet. The picker shows a spinner and waits to be told. */
    data object Loading : Listing

    data class Loaded(val rows: List<DocumentRow>) : Listing

    /** Said rather than shown as an empty folder, which is the lie this avoids. */
    data class Failed(val message: String) : Listing
}

/**
 * The library as a tree of documents, for whatever a platform puts in front of
 * it.
 *
 * Android's `DocumentsProvider` is the only caller and will be for a while --
 * iOS has a File Provider that wants a different shape -- but nothing here
 * names an Android class, and that is the point: what a document id is, when a
 * listing is asked for again, and which WebDAV verb each operation is are
 * decisions, and a decision inside a `ContentProvider` is one that only an
 * emulator can check.
 *
 * **A query answers from the cache and asks the server only when there is
 * nothing there.** Not when the cache is old: a provider tells the system its
 * listing changed and the system queries again, so a query that refetched on
 * age would notify, be queried, refetch and notify for ever. Freshness comes
 * from [refresh] and from writes, which are the two moments something actually
 * changed.
 */
class DocumentTree(
    private val instances: InstanceStore,
    private val connections: Connections,
    private val scope: CoroutineScope,
    /** Told when a folder's answer arrives or changes, so the platform can say so. */
    private val changed: (DocumentRef) -> Unit,
) {
    private val lock = Mutex()
    private val listings = mutableMapOf<DocumentRef, Listing>()
    private val fetching = mutableMapOf<DocumentRef, Job>()

    /** One per server somebody has signed in to. Empty is a legitimate answer. */
    suspend fun roots(): List<DocumentRoot> = instances.all().map {
        DocumentRoot(instanceId = it.id, title = TITLE, summary = summaryOf(it))
    }

    /**
     * What is in a folder, and a fetch started if nobody has asked before.
     *
     * Returns at once, always: a listing is a network round trip and a picker
     * that waited for one would be a picker that looked frozen.
     */
    suspend fun children(ref: DocumentRef): Listing = lock.withLock {
        listings[ref] ?: Listing.Loading.also {
            listings[ref] = it
            start(ref)
        }
    }

    /**
     * A folder's contents, waited for.
     *
     * [children] answers from a cache and tells the platform afterwards,
     * which is what a `DocumentsProvider` needs because its query must return
     * at once. A File Provider enumerator is the other shape: it is handed a
     * callback and wants the answer in it (stratus-app#105). Same client, no
     * cache in the way.
     */
    suspend fun listNow(ref: DocumentRef): List<DocumentRow> {
        val dav = requireNotNull(davFor(ref.instanceId)) { "no such server" }
        return dav.list(ref.davPath).map { rowOf(ref.instanceId, it) }.sortedForPeople()
    }

    /** Throws the cached answer away and asks again. What pull-to-refresh is. */
    suspend fun refresh(ref: DocumentRef) = lock.withLock {
        listings[ref] = Listing.Loading
        start(ref)
    }

    /**
     * One document.
     *
     * Read out of its parent's listing when that is there, because the picker
     * asks about a row it has just been given and a `PROPFIND` per row would
     * make a folder of a hundred files a hundred round trips.
     */
    suspend fun one(ref: DocumentRef): DocumentRow? {
        if (ref.path.isEmpty()) return rootRow(ref)
        val parent = ref.parent()
        val cached = parent?.let { lock.withLock { listings[it] } }
        if (cached is Listing.Loaded) {
            cached.rows.firstOrNull { it.ref == ref }?.let { return it }
        }
        val dav = davFor(ref.instanceId) ?: return null
        return rowOf(ref.instanceId, dav.stat(ref.davPath))
    }

    /** A folder for the directory type, an empty file for anything else. */
    suspend fun create(parent: DocumentRef, mimeType: String, displayName: String): DocumentRef {
        val dav = requireNotNull(davFor(parent.instanceId)) { "no such server" }
        val ref = parent.child(displayName)
        if (mimeType == MIME_DIRECTORY) {
            dav.makeCollection(ref.davPath)
        } else {
            dav.put(ref.davPath, ByteArray(0), mimeType.takeIf { it.isNotBlank() })
        }
        invalidate(parent)
        return ref
    }

    /** A rename is a rename: one path element, never a move across the tree. */
    suspend fun rename(ref: DocumentRef, displayName: String): DocumentRef {
        require('/' !in displayName && displayName.isNotBlank()) { "a name is not a path" }
        val dav = requireNotNull(davFor(ref.instanceId)) { "no such server" }
        val parent = requireNotNull(ref.parent()) { "a server's root has no name to change" }
        val destination = parent.child(displayName)
        dav.move(ref.davPath, destination.davPath)
        invalidate(parent)
        return destination
    }

    suspend fun delete(ref: DocumentRef) {
        val dav = requireNotNull(davFor(ref.instanceId)) { "no such server" }
        dav.delete(ref.davPath)
        ref.parent()?.let { invalidate(it) }
    }

    /**
     * A reader for a file somebody is going to seek around in.
     *
     * Built here rather than by the caller so that the protocol stays on this
     * side of the seam: a platform shell holds a [RangeReader] and asks it for
     * bytes, and never names an HTTP type.
     */
    fun reader(ref: DocumentRef, size: Long, scope: CoroutineScope): RangeReader =
        RangeReader(scope, size) { from, use ->
            val dav = requireNotNull(davFor(ref.instanceId)) { "no such server" }
            dav.read(ref.davPath, from until size, use)
        }

    /** The whole file onto local disk, for the one case a range cannot serve. */
    suspend fun download(ref: DocumentRef, toPath: String) {
        val dav = requireNotNull(davFor(ref.instanceId)) { "no such server" }
        dav.read(ref.davPath, null) { channel ->
            SystemFileSystem.sink(Path(toPath)).buffered().use { sink ->
                while (!channel.exhausted()) {
                    sink.transferFrom(channel.readRemaining(CHUNK))
                }
            }
        }
    }

    /**
     * Replaces what is there with a local file, which is what a `PUT` does and
     * what a picker means by saving.
     *
     * A path and not a stream: whoever wrote it seeked around in it, so it is
     * already on disk by the time it is sent, and streaming it from there is
     * what keeps a four-gigabyte video out of memory.
     */
    suspend fun upload(ref: DocumentRef, fromPath: String, contentType: String?) {
        val dav = requireNotNull(davFor(ref.instanceId)) { "no such server" }
        val file = Path(fromPath)
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0L
        SystemFileSystem.source(file).buffered().use { body ->
            dav.put(ref.davPath, size, body, contentType)
        }
        ref.parent()?.let { invalidate(it) }
    }

    /**
     * The server's own rendering of a picture, or null where there is none.
     *
     * The one thing here that knows it may be talking to a Stratus: `/thumb/`
     * is not in WebDAV and no other server has it. It costs nothing to be
     * wrong about -- a server without it answers something that is not an
     * image and the picker draws an icon, which is what it would have done
     * anyway. The request is authenticated like every other, and the
     * signature rides along because [ShareLinks] is also the one piece that
     * knows how that address is spelled.
     */
    suspend fun thumbnail(ref: DocumentRef, width: Int): ByteArray? {
        val instance = instances.instance(ref.instanceId) ?: return null
        val credentials = instances.credentials(ref.instanceId) ?: return null
        val connection = connections.to(instance) ?: return null
        val url = ShareLinks(instance.baseUrl, credentials)
            .thumbnail(ref.davPath, width, ShareLife.ADay, 0) ?: return null
        return runCatching {
            val response = connection.http.get(url)
            val type = response.headers[HttpHeaders.ContentType].orEmpty()
            if (response.status.isSuccess() && type.startsWith("image/")) response.bodyAsBytes() else null
        }.getOrNull()
    }

    /** What the platform shows when it has nowhere else to say it. */
    fun summaryOf(instance: Instance): String = originOf(instance.baseUrl).substringAfter("://")

    private suspend fun invalidate(ref: DocumentRef) {
        lock.withLock {
            listings[ref] = Listing.Loading
            start(ref)
        }
    }

    /** Called with [lock] held. */
    private fun start(ref: DocumentRef) {
        fetching.remove(ref)?.cancel()
        fetching[ref] = scope.launch {
            val answer = try {
                if (davFor(ref.instanceId) == null) {
                    Listing.Failed("that server is not signed in any more")
                } else {
                    Listing.Loaded(listNow(ref))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DavError) {
                Listing.Failed(e.message ?: "the server refused")
            } catch (e: Exception) {
                Listing.Failed(e.message ?: "the server could not be reached")
            }
            lock.withLock {
                listings[ref] = answer
                fetching.remove(ref)
            }
            changed(ref)
        }
    }

    private suspend fun davFor(instanceId: String): DavClient? = connectionFor(instanceId)?.dav

    private suspend fun connectionFor(instanceId: String): Connection? =
        instances.instance(instanceId)?.let { connections.to(it) }

    private suspend fun rootRow(ref: DocumentRef): DocumentRow? {
        val instance = instances.instance(ref.instanceId) ?: return null
        return DocumentRow(ref, summaryOf(instance), isDirectory = true, null, MIME_DIRECTORY, null)
    }

    private fun rowOf(instanceId: String, entry: DavResource): DocumentRow {
        val ref = DocumentRef.of(instanceId, entry.path)
        return DocumentRow(
            ref = ref,
            name = ref.name,
            isDirectory = entry.isDirectory,
            size = entry.size,
            contentType = if (entry.isDirectory) MIME_DIRECTORY else entry.contentType,
            lastModifiedEpochMs = entry.lastModifiedEpochMs,
            etag = entry.etag,
        )
    }

    private fun List<DocumentRow>.sortedForPeople(): List<DocumentRow> =
        sortedWith(compareByDescending<DocumentRow> { it.isDirectory }.thenBy { it.name.lowercase() })

    companion object {
        /** Android's own name for a folder, which the picker matches on exactly. */
        const val MIME_DIRECTORY = "vnd.android.document/directory"

        private const val TITLE = "Stratus"
        private const val CHUNK = 64L * 1024
    }
}
