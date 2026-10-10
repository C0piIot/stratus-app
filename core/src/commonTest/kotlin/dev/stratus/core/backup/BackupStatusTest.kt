package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.server.Server
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Roll(private val access: MediaAccess = MediaAccess.Full) : AssetSource {
    override suspend fun access() = access
    override suspend fun sources() = emptyList<MediaSource>()
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long) = emptyList<Asset>()
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource = Buffer()
}

class BackupStatusTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private var clock = 1_000_000L

    private fun status(access: MediaAccess = MediaAccess.Full, parked: Boolean = false) =
        BackupStatus(database, Roll(access), now = { clock }, parked = { parked })

    private suspend fun queue(path: String, size: Long = 100) {
        database.migrate()
        database.pending().add(
            PendingUpload(path = path, localId = "l", part = AssetPart.Still, size = size, contentType = null, takenAt = "t"),
        )
    }

    @Test
    fun whatTheSystemIsCarryingIsSaidAsSendingAndNotAsWaiting() = runTest {
        // The state somebody reads while an iOS backup is actually running:
        // "waiting for the next pass" over a transfer in progress is the
        // wrong thing to tell them (stratus-app#20).
        queue(path = "/a.jpg")
        database.pending().recordHandover("/a.jpg", "t-1", null)

        val state = status().of()
        assertTrue(state is BackupState.Waiting, "was $state")
        assertEquals(WaitingReason.InTheSystemsHands, state.reason)
    }

    @Test
    fun saysItIsWaitingForWifiRatherThanForTheNextPass() = runTest {
        // The next pass will park too, so "waiting for the next pass" over a
        // backup somebody deliberately stopped is the silence this surface
        // exists to remove (stratus-app#133).
        queue(path = "/a.jpg")

        val state = status(parked = true).of()
        assertTrue(state is BackupState.Waiting, "was $state")
        assertEquals(WaitingReason.ForWifi, state.reason)
    }

    @Test
    fun aBackoffOnAMeteredConnectionIsStillAboutTheWifi() = runTest {
        // The retry cannot happen either: what it is waiting for is the wifi.
        queue(path = "/a.jpg")
        database.pending().recordFailure("/a.jpg", "timeout", clock + 30_000)

        val state = status(parked = true).of()
        assertTrue(state is BackupState.Waiting, "was $state")
        assertEquals(WaitingReason.ForWifi, state.reason)
    }

    @Test
    fun saysSoWhenNothingHasEverHappened() = runTest {
        database.migrate()
        assertEquals(BackupState.NeverRun, status().of())
    }

    @Test
    fun anUnreadableLibraryOutranksEverythingElse() = runTest {
        // Nothing can be backed up at all, and silence here is precisely what a
        // broken backup looks like from the outside.
        queue(path = "/a.heic")
        val state = status(MediaAccess.None).of()
        assertEquals(BackupState.NeedsYou(AttentionReason.TheLibraryIsNotReadable, 0), state)
    }

    @Test
    fun saysWhatItIsSendingWhileItSendsIt() = runTest {
        queue(path = "/a.heic")
        database.journal().began(clock)
        database.journal().sending("/Photos/2026/09/a.heic")

        val state = status().of()
        assertTrue(state is BackupState.Working, "was $state")
        assertEquals("/Photos/2026/09/a.heic", state.current)
        assertEquals(1, state.left)
    }

    @Test
    fun tellsAPauseThatEndsItselfFromOneThatDoesNot() = runTest {
        // The distinction the whole type exists for.
        queue(path = "/a.heic")
        database.pending().recordFailure("/a.heic", "timeout", clock + 30_000)

        val waiting = status().of()
        assertTrue(waiting is BackupState.Waiting, "was $waiting")
        assertEquals(WaitingReason.ForARetry, waiting.reason)
        assertEquals(clock + 30_000, waiting.until)

        // The same row, given up on, is a different thing entirely.
        database.pending().recordFailure("/a.heic", "rejected", Long.MAX_VALUE)
        val needing = status().of()
        assertTrue(needing is BackupState.NeedsYou, "was $needing")
        assertEquals(AttentionReason.SomethingWillNotSend("rejected"), needing.reason)
    }

    @Test
    fun workThatIsSimplyNotRunningYetIsNotAFailure() = runTest {
        queue(path = "/a.heic")
        val state = status().of()
        assertTrue(state is BackupState.Waiting, "was $state")
        assertEquals(WaitingReason.ForTheNextPass, state.reason)
        assertEquals(null, state.until)
        assertEquals(100, state.bytesLeft)
    }

    @Test
    fun onePhotographThatWillNotGoOutranksAQueueThatIsMoving() = runTest {
        // The rest of the queue draining is no comfort to the one that will not.
        queue(path = "/gone.heic")
        queue(path = "/fine.heic")
        database.pending().recordFailure("/gone.heic", "forbidden", Long.MAX_VALUE)

        val state = status().of()
        assertTrue(state is BackupState.NeedsYou, "was $state")
        assertEquals(1, state.affected)
    }

    @Test
    fun saysWhenItLastFinishedOnceThereIsNothingLeft() = runTest {
        database.migrate()
        database.journal().began(clock)
        database.journal().ended(clock + 5_000, StoppedBecause.NothingLeft, uploaded = 12, failed = 0)

        assertEquals(BackupState.Idle(clock + 5_000, 12), status().of())
    }
}
