package dev.stratus.app.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A photograph on the phone ending up on the server, and what happens when the server is not there. */
class BackupTest : E2E() {

    private fun openBackupAndRun() {
        // The strip is the way in, whatever it says at the moment.
        ui.onAllNodes(hasClickAction() and hasText("ackup", substring = true))[0].performClick()
        tap("Back up now")
    }

    /**
     * A bare timeout here says only that a photograph did not arrive, which is
     * every way this can fail at once. So it says what the app was showing and
     * what the server actually holds -- the two halves of the answer.
     */
    private fun waitForTheServer(name: String, timeoutMs: Long = 90_000): Pair<String, Long> {
        var found: Pair<String, Long>? = null
        try {
            ui.waitUntil(timeoutMs) {
                found = Stratus.backedUp(name)
                found != null
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "$name never reached the server. The backup root holds " +
                    "${Stratus.underBackupRoot()}, and the app showed:\n${screen()}",
                e,
            )
        }
        return assertNotNull(found)
    }

    @Test
    fun aPhotographEndsUpOnTheServerWithAllItsBytes() {
        val bytes = Random.nextBytes(64_000)
        Phone.photograph("$unique.jpg", unique, bytes)
        Phone.grantPhotos()
        signedIn()
        turnBackupOn()

        openBackupAndRun()

        val (_, size) = waitForTheServer("$unique.jpg")
        assertEquals(bytes.size.toLong(), size)
    }

    /**
     * The rule stratus-app#124 is about, through the whole app: somebody
     * deletes a backed-up photograph on the server and the next pass leaves it
     * deleted.
     *
     * Worth an emulator because every layer is in it -- the settled record, the
     * queue and a real server -- and because the failure it guards against is
     * silent: a deletion that comes back looks like the app working.
     */
    @Test
    fun aPhotographDeletedOnTheServerIsNotPutBack() {
        Phone.photograph("$unique.jpg", unique, Random.nextBytes(10_000))
        Phone.grantPhotos()
        signedIn()
        turnBackupOn()

        openBackupAndRun()
        val (path, _) = waitForTheServer("$unique.jpg")

        Stratus.delete(path)
        // Already on the backup screen, so the strip is not there to be tapped
        // a second time -- only the button is.
        tap("Back up now")
        // The pass has to have finished before absence means anything.
        see("Everything is backed up", timeoutMs = 60_000)

        assertTrue(
            Stratus.backedUp("$unique.jpg") == null,
            "the deletion was undone: the root holds ${Stratus.underBackupRoot()}",
        )
    }

    // A server that is off is a reason to wait, not a verdict on the file.
    @Test
    fun aServerThatIsOffIsWaitedForAndTheBackupFinishesOnceItIsBack() {
        Phone.photograph("$unique.jpg", unique, Random.nextBytes(10_000))
        Phone.grantPhotos()
        signedIn()
        turnBackupOn()

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
        turnBackupOn()

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
