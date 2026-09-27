package dev.stratus.app.e2e

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserTest : E2E() {

    @Test
    fun walksIntoAFolderAndBackOutWithTheSystemGesture() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/inside.txt")
        signedIn()

        tap(unique)
        see("inside.txt")
        Phone.device.pressBack()
        see(unique)
        gone("inside.txt")
    }

    // stratus-app#82: something another client added shows without walking
    // out of the folder and back.
    @Test
    fun pullingTheListDownShowsWhatWasAddedSince() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/first.txt")
        signedIn()
        tap(unique)
        see("first.txt")

        Stratus.file("/$unique/second.txt")
        ui.onAllNodes(hasScrollAction())[0].performTouchInput { swipeDown() }

        see("second.txt")
    }

    @Test
    fun renamesAFileOnTheServer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/before.txt")
        signedIn()
        tap(unique)

        tap("before.txt")
        tap("Rename")
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNode(hasSetTextAction()).performTextInput("after.txt")
        tap("Rename")

        see("after.txt")
        assertEquals(listOf("after.txt"), Stratus.names("/$unique/"))
    }

    @Test
    fun deletingAsksFirstAndThenRemovesItFromTheServer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/doomed.txt")
        signedIn()
        tap(unique)

        tap("doomed.txt")
        tap("Delete")
        see("This cannot be undone")
        tap("Cancel")
        assertTrue(Stratus.exists("/$unique/doomed.txt"), "cancelling deleted it")

        tap("doomed.txt")
        tap("Delete")
        tap("Delete")
        gone("doomed.txt")
        assertFalse(Stratus.exists("/$unique/doomed.txt"))
    }

    @Test
    fun aFolderCanBeRenamedFromItsLongPressMenu() {
        Stratus.folder("/$unique/")
        signedIn()

        see(unique).performTouchInput { longClick() }
        tap("Rename")
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNode(hasSetTextAction()).performTextInput("${unique}b")
        tap("Rename")

        see("${unique}b")
        assertTrue(Stratus.exists("/${unique}b/"))
    }

    @Test
    fun downloadingKeepsACopyInDownloads() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/$unique.bin", bytes)
        signedIn()
        tap(unique)

        tap("$unique.bin")
        tap("Download")

        ui.waitUntil(20_000) { Phone.downloads("$unique.bin").isNotEmpty() }
        assertEquals(listOf(bytes.size.toLong()), Phone.downloads("$unique.bin"))
    }

    @Test
    fun openingAFileHandsItToTheSystemWithoutCrashing() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/note.txt")
        signedIn()
        tap(unique)

        tap("note.txt")
        tap("Open")
        // An emulator with no viewer still has the chooser, which says so.
        ui.waitUntil(10_000) { Phone.inFront() != Phone.context.packageName }
        Phone.backToApp()
        see("note.txt")
    }

    // No Play Services on this image, so this is the phone that has none.
    @Test
    fun withoutPlayServicesThereIsNoCastButtonAndNothingElseIsMissing() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/film.mp4")
        signedIn()
        tap(unique)

        tap("film.mp4")
        see("Open")
        gone("Cast to a screen", timeoutMs = 1_000)
    }
}
