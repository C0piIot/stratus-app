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
 *
 * **The term typed is never the name tapped**, which is not a style choice:
 * `tap` matches a node's text exactly, and the search field holds the term --
 * so a term equal to a result's name would be two things to press and a test
 * that passed or failed by which one it reached first.
 */
class SearchTest : E2E() {

    private fun search(term: String, inside: String? = null) {
        tap(Glyph.SEARCH)
        // The whole server is the default, so searching one folder is a chip
        // tapped first -- named by the folder, which is how it is found here.
        if (inside != null) tap(inside)
        ui.onNode(hasSetTextAction()).performTextInput(term)
        // The keyboard's own search key, which is how this runs: there is no
        // search per keystroke to fall back on.
        ui.onNode(hasSetTextAction()).performImeAction()
    }

    @Test
    fun findsAFileByAWordOfItsName() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/a lisbon tram.jpg")
        Stratus.file("/$unique/unrelated.txt")
        signedIn()

        search("lisbon")
        see("a lisbon tram.jpg")
        gone("unrelated.txt")
    }

    // A folder found opens in the browser rather than inside the search,
    // because what somebody wants from a folder is to be in it.
    @Test
    fun aFolderFoundOpensInTheBrowser() {
        Stratus.folder("/$unique/")
        Stratus.folder("/$unique/lisbon-album/")
        Stratus.file("/$unique/lisbon-album/inside.txt")
        signedIn()

        // "album" rather than the folder's whole name: the separators a name
        // is made of are word boundaries on the server, which is the promise
        // being leant on here as well as the one that keeps the tap unambiguous.
        search("album")
        tap("lisbon-album")

        see("inside.txt")
        onTheBrowser()
    }

    // The folder somebody is standing in is a scope of its own
    // (stratus-app#146, stratus-backend#329), and the e2e is where the whole
    // of it meets: a chip tapped here, a scope built there, and an answer
    // that is about one folder and not the library.
    @Test
    fun searchingInsideAFolderAnswersAboutThatFolder() {
        Stratus.folder("/$unique/")
        Stratus.folder("/$unique/here/")
        Stratus.folder("/$unique/there/")
        Stratus.file("/$unique/here/a lisbon tram.jpg")
        Stratus.file("/$unique/there/a lisbon ferry.jpg")
        signedIn()

        tap(unique)
        tap("here")

        search("lisbon", inside = "here")
        see("a lisbon tram.jpg")
        gone("a lisbon ferry.jpg")
    }

    @Test
    fun aSearchThatMatchesNothingSaysSoRatherThanLookingBroken() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/something.txt")
        signedIn()

        search("nothingiscalledthisanywhere")
        see("Nothing matches")
    }
}
