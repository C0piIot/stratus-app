package dev.stratus.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.stratus.core.cast.CastController
import dev.stratus.core.dav.DavResource
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.SearchController
import dev.stratus.core.files.SearchState
import dev.stratus.ui.Footnote
import dev.stratus.ui.Page

/**
 * Finding a file by a word of its name (stratus-app#143).
 *
 * **The rows are the browser's rows**, down to the menu on a long press: a
 * file found here opens, downloads, renames, shares and casts exactly as the
 * same file does in a listing, because it is the same file and a second kind
 * of row would be a second set of things it can do. A folder found here opens
 * in the browser rather than inside this screen -- what somebody wants from a
 * folder is to be in it.
 */
@Composable
fun SearchScreen(
    search: SearchController,
    browser: BrowserController,
    cast: CastController?,
    /** Walks the browser to a folder and leaves this screen behind. */
    onOpenFolder: (DavResource) -> Unit,
    onClose: () -> Unit,
) {
    val state by search.state.collectAsState()

    Page("Search", onBack = onClose, scroll = false) {
        OutlinedTextField(
            value = state.term,
            onValueChange = search::typed,
            label = { Text("A word in the name") },
            singleLine = true,
            // Not a search per keystroke: the answer has no ranking to improve
            // as a word grows, and on somebody's own server every letter would
            // be a full-text query whose answer is thrown away.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search.submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        when {
            // Nothing is known yet, which is a moment and not a state worth
            // drawing something for.
            state.offered == null && state.ran == null -> {}

            state.offered == false -> Explain(
                "This server cannot search.",
                "Searching over WebDAV is a standard — RFC 5323 — that very few servers " +
                    "implement. Stratus does. Nothing else is wrong: everything on this " +
                    "server still browses, opens and backs up as it did.",
            )

            state.unreachable != null -> Explain(
                "The server did not answer.",
                state.unreachable.orEmpty(),
            )

            state.failure != null -> Explain(
                "The search did not go through.",
                describe(state.failure!!),
            )

            state.empty -> Explain(
                "Nothing matches \"${state.ran}\".",
                "Whole words of a name, not parts of one: searching \"holi\" does not " +
                    "find \"holiday\". What is inside a file is not searched.",
            )

            else -> Results(state, browser, cast, onOpenFolder)
        }
    }
}

@Composable
private fun Results(
    state: SearchState,
    browser: BrowserController,
    cast: CastController?,
    onOpenFolder: (DavResource) -> Unit,
) {
    // Said above the list rather than at the end of it: somebody who has to
    // scroll to the bottom to learn the answer was cut has already decided
    // that what they wanted is not here.
    if (state.cut) {
        Footnote(
            "These are the first ${state.found.size} and there are more. There is no " +
                "second page — a narrower word is how you get to the rest.",
        )
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.found, key = { it.path }) { entry ->
            Entry(entry, browser, cast, onEnter = onOpenFolder)
            HorizontalDivider()
        }
    }
}

/** A headline and the sentence under it, which is every answer that is not a list. */
@Composable
private fun Explain(headline: String, detail: String) {
    Column(
        Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
