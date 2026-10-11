package dev.stratus.core.files

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Finding a file by a word of its name (stratus-app#143).
 *
 * **Whether the server can do this at all is the first state, not an error.**
 * `SEARCH` is RFC 5323 and almost no WebDAV server implements it, so the
 * ordinary answer for a server that is not a Stratus is "cannot", and a screen
 * that reported that as a failure would be calling the normal case broken.
 * The probe is one `OPTIONS` and its answer does not change while the app
 * runs, so it is asked once and kept -- the same bargain the tus probe makes,
 * one level coarser because this one belongs to no pass.
 *
 * Here and not in a `remember` on the screen for the reason stratus-app#75
 * cost: state a composable owns is state no test reaches.
 */
class SearchController(
    private val dav: DavClient,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutable.asStateFlow()

    private var running: Job? = null

    /** Asks the server what it can do, once. */
    fun start() {
        if (mutable.value.offered != null) return
        scope.launch {
            mutable.value = mutable.value.copy(
                offered = try {
                    dav.canSearch()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A server that could not be reached is not a server that
                    // cannot search, but there is nothing else to say yet and
                    // the next attempt asks again.
                    null
                },
            )
        }
    }

    fun typed(text: String) {
        mutable.value = mutable.value.copy(term = text)
    }

    /**
     * Runs the search, or does nothing when there is nothing to look for.
     *
     * Deliberately not on every keystroke: the answer has no ranking and the
     * server has no cursor, so a word half-typed is a query whose answer is
     * thrown away -- and on somebody's own server it is a full-text query per
     * letter. The button and the keyboard's own search key are what run it.
     */
    fun submit() {
        val term = mutable.value.term.trim()
        if (term.isEmpty()) return

        running?.cancel()
        mutable.value = mutable.value.copy(
            busy = true, failure = null, unreachable = null, found = emptyList(), cut = false,
        )
        running = scope.launch {
            try {
                val answer = dav.search(term)
                mutable.value = mutable.value.copy(
                    busy = false,
                    ran = term,
                    found = answer.found.sortedForPeople(),
                    cut = answer.cut,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DavError.MethodNotAllowed) {
                // The server answered and said it has no such method, which is
                // the probe's answer arriving late. Not a failure: the screen
                // explains instead.
                mutable.value = mutable.value.copy(busy = false, offered = false)
            } catch (e: DavError) {
                mutable.value = mutable.value.copy(busy = false, failure = e)
            } catch (e: Exception) {
                // A lost server is an ordinary thing and not a crash: the one
                // the e2e suite found first (stratus-app#78) was exactly this
                // shape, uncaught, one screen along.
                mutable.value = mutable.value.copy(busy = false, unreachable = e.message ?: "the server did not answer")
            }
        }
    }

    fun clear() {
        running?.cancel()
        mutable.value = mutable.value.copy(
            term = "", ran = null, found = emptyList(), cut = false,
            failure = null, unreachable = null, busy = false,
        )
    }
}

/**
 * What the search screen draws.
 *
 * [offered] is null until the server has been asked, false where it cannot
 * search and true where it can; [ran] is the term the results on screen are
 * the answer to, which is not [term] while somebody is typing the next one.
 */
data class SearchState(
    val term: String = "",
    val ran: String? = null,
    val offered: Boolean? = null,
    val busy: Boolean = false,
    val found: List<DavResource> = emptyList(),
    /** The server stopped early, so these are not all of them (RFC 5323 2.3.1). */
    val cut: Boolean = false,
    val failure: DavError? = null,
    /** The server could not be reached at all, which is not the server refusing. */
    val unreachable: String? = null,
) {
    /** A search that ran and found nothing, which is an answer and not an error. */
    val empty: Boolean
        get() = ran != null && !busy && found.isEmpty() && failure == null && unreachable == null
}
