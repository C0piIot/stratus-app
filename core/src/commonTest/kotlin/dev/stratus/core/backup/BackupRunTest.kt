package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.dav.DavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import dev.stratus.core.server.Server
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
    val boundedBy = mutableListOf<Long>()
    override suspend fun access() = access
    override suspend fun sources() = emptyList<MediaSource>()
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> {
        askedFor += from
        boundedBy += addedAfterEpochMs
        // The bound as both platforms apply it: no bound at zero, and a
        // photograph whose arrival is unknown is never skipped by one.
        return assets.filter { addedAfterEpochMs <= 0 || it.addedAtEpochMs <= 0 || it.addedAtEpochMs > addedAfterEpochMs }
    }
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        Buffer().apply { write(ByteArray(10), 0, 10) }
}

private class Always(private val outcome: UploadOutcome) : Transport {
    override val resumable = false
    override suspend fun send(target: UploadTarget, resume: Resume?, body: UploadBody): UploadOutcome = outcome
}

class BackupRunTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val instance = Server("i-1", "https://host/dav/", "edu", sources = setOf("Camera"))

    private fun asset(day: Int, addedAt: Long = utcMillis(2026, 9, day)) =
        Asset("local-$day", utcMillis(2026, 9, day), addedAt, "IMG_$day.HEIC", 10)

    /** A server with nothing on it, which is what a first pass meets. */
    private fun emptyServer() = MockEngine { respond("", HttpStatusCode.NotFound) }

    private suspend fun run(
        roll: RollOf,
        outcome: UploadOutcome = UploadOutcome.Done("etag"),
        engine: MockEngine = emptyServer(),
    ): BackupRun {
        database.migrate()
        return BackupRun(
            source = roll,
            prepareFor = {
                val layout = RemoteLayout(Server.DEFAULT_BACKUP_ROOT)
                val cache = database.cache()
                ServerBackup(
                    queue = UploadQueue(
                        layout = layout,
                        pending = database.pending(),
                        cache = cache,
                        source = roll,
                        transport = Always(outcome),
                        directories = { },
                    ),
                    index = BackupIndex(layout, cache, DavClient(HttpClient(engine), "https://host/dav/")),
                    mark = database.mark(),
                )
            },
            journalFor = { database.journal() },
        )
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
    fun workInTheSystemsHandsIsNotCalledWaitingToRetry() = runTest {
        // It is not waiting on us at all: on iOS a transfer outlives the pass
        // that started it, and telling somebody "waiting to retry" over a
        // backup that is running would be the wrong thing to say
        // (stratus-app#20).
        val roll = RollOf(listOf(asset(1), asset(2)))
        val report = run(roll, UploadOutcome.HandedOver("t-1")).once(instance)

        assertEquals(StoppedBecause.InFlight, report.stopped)
        assertEquals(2, report.handedOver)
        assertEquals(0, report.uploaded, "nothing has arrived yet, and the report must not pretend")
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

    @Test
    fun theSecondPassOnlyLooksAtWhatArrivedAfterTheFirst() = runTest {
        val roll = RollOf(listOf(asset(1), asset(2)))
        val run = run(roll)
        run.once(instance)
        run.once(instance)

        // The first pass looks at everything, and the second starts where it
        // stopped: a phone with forty thousand photographs should not read all
        // of them to find the three that are new.
        assertEquals(0L, roll.boundedBy.first())
        assertEquals(utcMillis(2026, 9, 2), roll.boundedBy.last())
    }

    @Test
    fun theBoundStopsAtTheOldestThingStillOwed() = runTest {
        // A photograph that will not send holds the bound behind it, or the
        // next pass enumerates past it and nobody ever tries it again.
        val roll = RollOf(listOf(asset(1), asset(2)))
        val run = run(roll, UploadOutcome.Failed(FailureKind.Permanent, "refused"))
        run.once(instance)
        run.once(instance)

        assertEquals(utcMillis(2026, 9, 1) - 1, roll.boundedBy.last())
    }

    @Test
    fun aPhotographWithNoAddedDateFreezesTheBound() = runTest {
        // Which is every iOS below 26, where PhotoKit has no such thing to
        // report. The bound decides nothing, so standing still is only slower.
        val roll = RollOf(listOf(asset(1, addedAt = 0), asset(2)))
        val run = run(roll)
        run.once(instance)
        run.once(instance)

        assertEquals(listOf(0L, 0L), roll.boundedBy)
    }
}
