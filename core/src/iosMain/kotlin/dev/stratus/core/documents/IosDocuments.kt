package dev.stratus.core.documents

import dev.stratus.core.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the File Provider extension talks to (stratus-app#105).
 *
 * The extension is Swift and the decisions are not, so this is the whole of
 * the join: callbacks in the shape Swift already has to write, plain lists and
 * strings, and no Kotlin type that is awkward from the other side -- no
 * `Listing`, no sealed class, no suspend function for Swift to bridge.
 *
 * It is deliberately thicker than the Android equivalent, which needed none of
 * this because `DocumentsProvider` can block on a binder thread. The payoff is
 * where the work happens: this compiles on the ordinary runner in a minute and
 * a half, and every line of Swift costs a Mac.
 *
 * A process of its own, so it builds a container of its own. Nothing is shared
 * with the app but the keychain, which is the point of the access group.
 */
class IosDocuments {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Nothing to notify: an enumerator is asked again when the system wants
    // an answer, so there is nobody waiting to be told one arrived.
    private val tree = appContainer().documents(scope) { }

    /** Every server signed in to, which is one File Provider domain each. */
    fun roots(done: (List<DocumentRoot>?, String?) -> Unit) = answer(done) { tree.roots() }

    fun children(id: String, done: (List<DocumentRow>?, String?) -> Unit) =
        answer(done) { tree.listNow(DocumentRef.parse(id)) }

    fun one(id: String, done: (DocumentRow?, String?) -> Unit) =
        answer(done) { tree.one(DocumentRef.parse(id)) }

    /** The bytes onto local disk, which is what materialising an item is. */
    fun download(id: String, toPath: String, done: (String?) -> Unit) =
        report(done) { tree.download(DocumentRef.parse(id), toPath) }

    /** A file edited in another app, on its way back. */
    fun upload(id: String, fromPath: String, contentType: String?, done: (String?) -> Unit) =
        report(done) { tree.upload(DocumentRef.parse(id), fromPath, contentType) }

    fun delete(id: String, done: (String?) -> Unit) =
        report(done) { tree.delete(DocumentRef.parse(id)) }

    fun rename(id: String, to: String, done: (DocumentRow?, String?) -> Unit) =
        answer(done) { tree.one(tree.rename(DocumentRef.parse(id), to)) }

    fun createFolder(parentId: String, name: String, done: (DocumentRow?, String?) -> Unit) =
        answer(done) { tree.one(tree.create(DocumentRef.parse(parentId), DocumentTree.MIME_DIRECTORY, name)) }

    /**
     * A message and not a `Throwable`: what the other side does with it is
     * build an `NSError`, and a string is the only part of an exception that
     * survives that trip anyway.
     */
    private fun <T> answer(done: (T?, String?) -> Unit, work: suspend () -> T) {
        scope.launch {
            try {
                done(work(), null)
            } catch (e: Exception) {
                done(null, e.message ?: "the server could not be reached")
            }
        }
    }

    private fun report(done: (String?) -> Unit, work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
                done(null)
            } catch (e: Exception) {
                done(e.message ?: "the server could not be reached")
            }
        }
    }
}
