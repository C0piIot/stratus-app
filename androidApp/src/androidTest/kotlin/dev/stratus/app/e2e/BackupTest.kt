package dev.stratus.app.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A photograph on the phone ending up on the server, and what happens when the server is not there. */
class BackupTest : E2E() {

    private fun backupOn() {
        openServers()
        ui.onAllNodes(isToggleable())[0].performClick()
        tap("Back")
    }

    private fun openBackupAndRun() {
        // The strip is the way in, whatever it says at the moment.
        ui.onAllNodes(hasClickAction() and hasText("ackup", substring = true))[0].performClick()
        tap("Back up now")
    }

    private fun waitForTheServer(name: String, timeoutMs: Long = 90_000): Pair<String, Long> {
        var found: Pair<String, Long>? = null
        ui.waitUntil(timeoutMs) {
            found = Stratus.backedUp(name)
            found != null
        }
        return assertNotNull(found)
    }

    @Test
    fun aPhotographEndsUpOnTheServerWithAllItsBytes() {
        val bytes = Random.nextBytes(64_000)
        Phone.photograph("$unique.jpg", unique, bytes)
        Phone.grantPhotos()
        signedIn()
        backupOn()

        openBackupAndRun()

        val (_, size) = waitForTheServer("$unique.jpg")
        assertEquals(bytes.size.toLong(), size)
    }

    // A server that is off is a reason to wait, not a verdict on the file.
    @Test
    fun aServerThatIsOffIsWaitedForAndTheBackupFinishesOnceItIsBack() {
        Phone.photograph("$unique.jpg", unique, Random.nextBytes(10_000))
        Phone.grantPhotos()
        signedIn()
        backupOn()

        link.down()
        openBackupAndRun()
        see("waiting", timeoutMs = 60_000)
        assertTrue(Stratus.backedUp("$unique.jpg") == null)
        ui.onAllNodes(hasText("will not upload", substring = true))
            .fetchSemanticsNodes().let { assertTrue(it.isEmpty(), "a server being off was taken as the file being bad") }

        link.up()
        tap("Back up now")
        waitForTheServer("$unique.jpg")
    }

    // tus exists for exactly this: the second pass carries on rather than
    // sending the whole thing again.
    @Test
    fun anUploadCutHalfwayResumesWhereItStopped() {
        val bytes = Random.nextBytes(6_000_000)
        Phone.photograph("$unique.jpg", unique, bytes)
        Phone.grantPhotos()
        signedIn()
        backupOn()

        link.cutUploadAfter(2_000_000)
        openBackupAndRun()
        // Resumed within the pass or on the next one, whichever the queue
        // decides; a second tap is harmless if the first already finished it.
        if (Stratus.backedUp("$unique.jpg") == null) {
            ui.waitUntil(90_000) {
                Stratus.backedUp("$unique.jpg") != null ||
                    ui.onAllNodes(hasText("waiting", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            if (Stratus.backedUp("$unique.jpg") == null) tap("Back up now")
        }
        val (_, size) = waitForTheServer("$unique.jpg", timeoutMs = 120_000)
        assertEquals(bytes.size.toLong(), size)

        // Two million bytes before the cut and the rest after it is about the
        // file once; starting over would be the two million plus all of it.
        val sent = link.sent.get()
        assertTrue(
            sent < bytes.size * 1.15,
            "$sent bytes went out for a ${bytes.size}-byte file cut at two million: it started again",
        )
    }
}
