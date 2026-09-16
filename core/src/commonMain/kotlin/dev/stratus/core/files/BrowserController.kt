package dev.stratus.core.files

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlinx.io.Sink

/**
 * Walking the tree, and the four things that can be done to what is in it.
 *
 * Every verb here is standard WebDAV, so this works against any server: browse
 * is PROPFIND, download is GET, rename is MOVE and delete is DELETE. The limits
 * come with them, and the one that shows is renaming a folder with anything in
 * it -- refused by Stratus today, and said so in those words rather than as a
 * generic failure that reads like a broken app.
 */
class BrowserController(
    private val dav: DavClient,
    private val handoff: FileHandoff,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = mutable.asStateFlow()

    private var running: Job? = null

    fun start() = go(mutable.value.path)

    fun enter(directory: DavResource) = go(directory.path)

    /** True when there was somewhere to go. The root is the end of the line. */
    fun goUp(): Boolean {
        val here = mutable.value.path.trimEnd('/')
        if (here.isEmpty()) return false
        go(here.substringBeforeLast('/').ifEmpty { "" } + "/")
        return true
    }

    fun refresh() = go(mutable.value.path)

    private fun go(path: String) {
        running?.cancel()
        mutable.value = mutable.value.copy(path = path, busy = true, failure = null, pending = null)
        running = scope.launch {
            try {
                mutable.value = mutable.value.copy(entries = dav.list(path).sortedForPeople(), busy = false)
            } catch (e: DavError) {
                mutable.value = mutable.value.copy(busy = false, entries = emptyList(), failure = BrowserFailure.Listing(e))
            }
        }
    }

    fun openFile(entry: DavResource) = transfer(entry) { name, type, body -> handoff.open(name, type, body) }

    fun saveFile(entry: DavResource) = transfer(entry) { name, type, body -> handoff.save(name, type, body) }

    private fun transfer(
        entry: DavResource,
        hand: suspend (String, String?, suspend (Sink) -> Unit) -> Unit,
    ) {
        running?.cancel()
        mutable.value = mutable.value.copy(busy = true, failure = null)
        running = scope.launch {
            try {
                hand(entry.name, entry.contentType) { sink ->
                    dav.read(entry.path) { channel -> channel.writeTo(sink) }
                }
                mutable.value = mutable.value.copy(busy = false)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(busy = false, failure = BrowserFailure.Transfer(entry.name))
            }
        }
    }

    fun ask(confirmation: Confirmation) {
        mutable.value = mutable.value.copy(pending = confirmation, failure = null)
    }

    fun dismiss() {
        mutable.value = mutable.value.copy(pending = null)
    }

    /** Deletes what [ask] last put up. Does nothing if nothing was asked about. */
    fun confirmDelete() {
        val target = (mutable.value.pending as? Confirmation.Delete)?.target ?: return
        act(target) { dav.delete(target.path) }
    }

    fun confirmRename(to: String) {
        val target = (mutable.value.pending as? Confirmation.Rename)?.target ?: return
        val name = to.trim()
        if (name.isEmpty() || '/' in name) return
        // A rename is a rename: reducing it to one path element is what stops a
        // typed path quietly carrying the file across the tree.
        val destination = target.path.trimEnd('/').substringBeforeLast('/') + "/" + name
        act(target) { dav.move(target.path, destination) }
    }

    private fun act(target: DavResource, work: suspend () -> Unit) {
        running?.cancel()
        mutable.value = mutable.value.copy(busy = true, pending = null, failure = null)
        running = scope.launch {
            try {
                work()
                refresh()
            } catch (e: DavError) {
                mutable.value = mutable.value.copy(busy = false, failure = classify(e, target))
            }
        }
    }

    /**
     * A 409 on a directory is the server saying it cannot move one that still has
     * contents. It says the same 409 when a destination's parent is missing, and
     * cannot tell them apart for us -- but we know what we asked for.
     */
    private fun classify(error: DavError, target: DavResource): BrowserFailure =
        if (error is DavError.Conflict && target.isDirectory) {
            BrowserFailure.RenameNeedsAnEmptyFolder(target.name)
        } else {
            BrowserFailure.Operation(error)
        }

    /**
     * Moves the bytes across a chunk at a time.
     *
     * Never `readRemaining()` with no bound, which would put the whole file in
     * memory -- the files this app exists for are videos.
     */
    private suspend fun ByteReadChannel.writeTo(sink: Sink) {
        while (!exhausted()) {
            sink.transferFrom(readRemaining(CHUNK_BYTES))
        }
        sink.flush()
    }

    private fun List<DavResource>.sortedForPeople(): List<DavResource> =
        sortedWith(compareByDescending<DavResource> { it.isDirectory }.thenBy { it.name.lowercase() })

    private companion object {
        const val CHUNK_BYTES = 64L * 1024
    }
}
