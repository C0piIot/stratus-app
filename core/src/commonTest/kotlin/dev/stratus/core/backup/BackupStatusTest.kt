package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.instance.Instance
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
    private val instance = Instance("i-1", "https://host/dav/", "edu")
    private val other = Instance("i-2", "https://other/dav/", "edu")

    private fun status(access: MediaAccess = MediaAccess.Full) =
        BackupStatus(database, Roll(access), now = { clock })

    private suspend fun queue(id: String = instance.id, path: String, size: Long = 100) {
        database.migrate()
        database.pendingFor(id).add(
            PendingUpload(path = path, localId = "l", part = AssetPart.Still, size = size, contentType = null, takenAt = "t"),
        )
    }

    @Test
    fun saysSoWhenNothingHasEverHappened() = runTest {
        database.migrate()
        assertEquals(BackupState.NeverRun, status().of(instance))
    }

    @Test
    fun anUnreadableLibraryOutranksEverythingElse() = runTest {
        // Nothing can be backed up at all, and silence here is precisely what a
        // broken backup looks like from the outside.
        queue(path = "/a.heic")
        val state = status(MediaAccess.None).of(instance)
        assertEquals(BackupState.NeedsYou(AttentionReason.TheLibraryIsNotReadable, 0), state)
    }

    @Test
    fun saysWhatItIsSendingWhileItSendsIt() = runTest {
        queue(path = "/a.heic")
        database.journalFor(instance.id).began(clock)
        database.journalFor(instance.id).sending("/Photos/2026/09/a.heic")

        val state = status().of(instance)
        assertTrue(state is BackupState.Working, "was $state")
        assertEquals("/Photos/2026/09/a.heic", state.current)
        assertEquals(1, state.left)
    }

    @Test
    fun tellsAPauseThatEndsItselfFromOneThatDoesNot() = runTest {
        // The distinction the whole type exists for.
        queue(path = "/a.heic")
        database.pendingFor(instance.id).recordFailure("/a.heic", "timeout", clock + 30_000)

        val waiting = status().of(instance)
        assertTrue(waiting is BackupState.Waiting, "was $waiting")
        assertEquals(WaitingReason.ForARetry, waiting.reason)
        assertEquals(clock + 30_000, waiting.until)

        // The same row, given up on, is a different thing entirely.
        database.pendingFor(instance.id).recordFailure("/a.heic", "rejected", Long.MAX_VALUE)
        val needing = status().of(instance)
        assertTrue(needing is BackupState.NeedsYou, "was $needing")
        assertEquals(AttentionReason.SomethingWillNotSend("rejected"), needing.reason)
    }

    @Test
    fun workThatIsSimplyNotRunningYetIsNotAFailure() = runTest {
        queue(path = "/a.heic")
        val state = status().of(instance)
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
        database.pendingFor(instance.id).recordFailure("/gone.heic", "forbidden", Long.MAX_VALUE)

        val state = status().of(instance)
        assertTrue(state is BackupState.NeedsYou, "was $state")
        assertEquals(1, state.affected)
    }

    @Test
    fun saysWhenItLastFinishedOnceThereIsNothingLeft() = runTest {
        database.migrate()
        database.journalFor(instance.id).began(clock)
        database.journalFor(instance.id).ended(clock + 5_000, StoppedBecause.NothingLeft, uploaded = 12, failed = 0)

        assertEquals(BackupState.Idle(clock + 5_000, 12), status().of(instance))
    }

    @Test
    fun showsTheServerThatNeedsSomebodyRatherThanAnAverage() = runTest {
        // Half broken must not read as working.
        queue(id = instance.id, path = "/fine.heic")
        queue(id = other.id, path = "/gone.heic")
        database.pendingFor(other.id).recordFailure("/gone.heic", "rejected", Long.MAX_VALUE)

        val state = status().across(listOf(instance, other))
        assertTrue(state is BackupState.NeedsYou, "was $state")
        assertEquals(1, state.affected)
    }

    @Test
    fun addsUpWhatIsLeftAcrossServers() = runTest {
        queue(id = instance.id, path = "/a.heic", size = 100)
        queue(id = other.id, path = "/b.heic", size = 250)

        val state = status().across(listOf(instance, other))
        assertTrue(state is BackupState.Waiting, "was $state")
        assertEquals(2, state.left)
        assertEquals(350, state.bytesLeft)
    }
}
