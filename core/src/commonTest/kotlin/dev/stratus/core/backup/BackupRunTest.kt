package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.instance.Instance
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RollOf(
    private val assets: List<Asset>,
    private val access: MediaAccess = MediaAccess.Full,
) : AssetSource {
    val askedFor = mutableListOf<Set<String>>()
    override suspend fun access() = access
    override suspend fun sources() = emptyList<MediaSource>()
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> {
        askedFor += from
        return assets
    }
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        Buffer().apply { write(ByteArray(10), 0, 10) }
}

private class Always(private val outcome: UploadOutcome) : Transport {
    override val resumable = false
    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome = outcome
}

class BackupRunTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val instance = Instance("i-1", "https://host/dav/", "edu", sources = setOf("Camera"))

    private fun asset(day: Int) =
        Asset("local-$day", utcMillis(2026, 9, day), "IMG_$day.HEIC", 10)

    private suspend fun run(
        roll: RollOf,
        outcome: UploadOutcome = UploadOutcome.Done("etag"),
    ): BackupRun {
        database.migrate()
        return BackupRun(roll) { forInstance ->
            UploadQueue(
                layout = RemoteLayout(forInstance.backupRoot),
                pending = database.pendingFor(forInstance.id),
                cache = database.cacheFor(forInstance.id),
                source = roll,
                transport = Always(outcome),
                directories = { },
            )
        }
    }

    @Test
    fun queuesWhatIsMissingAndSendsIt() = runTest {
        val roll = RollOf(listOf(asset(1), asset(2)))
        val report = run(roll).once(instance)

        assertEquals(2, report.queued)
        assertEquals(2, report.uploaded)
        assertEquals(StoppedBecause.NothingLeft, report.stopped)
    }

    @Test
    fun looksOnlyWhereThisInstanceWasTold() = runTest {
        // Sources are per instance: everything to the NAS, only Camera to the VPS.
        val roll = RollOf(listOf(asset(1)))
        run(roll).once(instance)
        assertEquals(listOf(setOf("Camera")), roll.askedFor)
    }

    @Test
    fun saysSoWhenTheLibraryCannotBeRead() = runTest {
        // A refused permission is a state to report, not an empty camera roll --
        // which is what a backup silently doing nothing looks like.
        val roll = RollOf(listOf(asset(1)), access = MediaAccess.None)
        val report = run(roll).once(instance)

        assertEquals(StoppedBecause.NoAccessToTheLibrary, report.stopped)
        assertEquals(0, report.queued)
        assertEquals(emptyList(), roll.askedFor)
    }

    @Test
    fun stopsBetweenFilesWhenAskedTo() = runTest {
        // What the system reclaiming the app looks like. Nothing is lost by
        // stopping here because the queue is in the database.
        val roll = RollOf(listOf(asset(1), asset(2), asset(3)))
        var allowed = 2
        val report = run(roll).once(instance, keepGoing = { allowed-- > 0 })

        assertEquals(2, report.uploaded)
        assertEquals(StoppedBecause.AskedTo, report.stopped)
    }

    @Test
    fun doesNotClaimToHaveFinishedWhileWorkIsWaiting() = runTest {
        // Idle means nothing is runnable, not that nothing is left. A screen
        // saying "finished" over a backoff would be lying.
        val roll = RollOf(listOf(asset(1)))
        val report = run(roll, UploadOutcome.Failed(FailureKind.Transient, "timeout")).once(instance)

        assertEquals(StoppedBecause.WaitingToRetry, report.stopped)
        assertEquals(0, report.uploaded)
    }

    @Test
    fun countsWhatItGaveUpOn() = runTest {
        val roll = RollOf(listOf(asset(1)))
        val report = run(roll, UploadOutcome.Failed(FailureKind.Permanent, "rejected")).once(instance)

        assertEquals(1, report.failed)
        // Still outstanding, because somebody has to be told about it.
        assertEquals(StoppedBecause.WaitingToRetry, report.stopped)
    }

    @Test
    fun reportsEveryStepToWhoeverIsWatching() = runTest {
        // The status screen in #21 is built out of exactly this.
        val seen = mutableListOf<QueueStep>()
        run(RollOf(listOf(asset(1)))).once(instance, onStep = { seen += it })

        assertTrue(seen.any { it is QueueStep.Uploaded }, seen.toString())
        assertTrue(seen.last() is QueueStep.Idle, seen.toString())
    }
}
