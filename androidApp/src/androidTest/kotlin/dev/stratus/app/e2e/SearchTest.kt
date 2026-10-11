package dev.stratus.app.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import dev.stratus.ui.Glyph
import org.junit.Test

/**
 * Searching, end to end against a real backend (stratus-app#143).
 *
 * What the JVM tests cannot see is whether the whole of it meets: the probe
 * that decides the bar carries a glyph at all, a query built here and parsed
 * there, and a result that behaves like a row rather than like a sentence.
 */
class SearchTest : E2E() {

    private fun search(term: String) {
        tap(Glyph.SEARCH)
        ui.onNode(hasSetTextAction()).performTextInput(term)
        // The keyboard's own search key, which is how this runs: there is no
        // search per keystroke to fall back on.
        ui.onNode(hasSetTextAction()).performImeAction()
    }

    @Test
    fun findsAFileByAWordOfItsNameAndOpensWhereItIs() {
        Stratus.folder("/files/$unique/")
        Stratus.file("/files/$unique/a lisbon tram.jpg")
        Stratus.file("/files/$unique/unrelated.txt")
        signedIn()

        search("lisbon")
        see("a lisbon tram.jpg")
        gone("unrelated.txt")

        // A folder found opens in the browser rather than inside the search,
        // because what somebody wants from a folder is to be in it.
        goBack()
        onTheBrowser()
        search(unique)
        tap(unique)
        see("a lisbon tram.jpg")
        see(Glyph.SETTINGS)
    }

    @Test
    fun aSearchThatMatchesNothingSaysSoRatherThanLookingBroken() {
        Stratus.file("/files/$unique.txt")
        signedIn()

        search("nothingiscalledthisanywhere")
        see("Nothing matches")
    }
}
