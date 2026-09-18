package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeAssets(private val bytes: ByteArray = ByteArray(100)) : AssetSource {
    val openedFrom = mutableListOf<Long>()
    override suspend fun access() = MediaAccess.Full
    override suspend fun sources(): List<MediaSource> = emptyList()
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> = emptyList()
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource {
        openedFrom += from
        return Buffer().apply { write(bytes, from.toInt(), bytes.size) }
    }
}

private class FakeTransport(
    override val resumable: Boolean = false,
    private val answers: MutableList<UploadOutcome>,
) : Transport {
    val sent = mutableListOf<Pair<UploadTarget, Resume?>>()
    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome {
        sent += target to resume
        open(resume?.offset ?: 0)
        return if (answers.size > 1) answers.removeFirst() else answers.first()
    }
}

private class FakeDirectories : DirectoryMaker {
    val made = mutableListOf<String>()
    override suspend fun ensure(path: String) { made += path }
}

class UploadQueueTest {

    private val layout = RemoteLayout()
    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val assets = FakeAssets()
    private val directories = FakeDirectories()
    private var clock = 1_000L

    private suspend fun queue(
        transport: Transport = FakeTransport(answers = mutableListOf(UploadOutcome.Done("etag"))),
    ): UploadQueue {
        database.migrate()
        return UploadQueue(
            layout = layout,
            pending = database.pendingFor("instance-a"),
            cache = database.cacheFor("instance-a"),
            source = assets,
            transport = transport,
            directories = directories,
            now = { clock },
        )
    }

    private fun asset(day: Int, name: String = "IMG_$day.HEIC", motion: MotionPart? = null) =
        Asset("local-$day", utcMillis(2026, 9, day, 12, 0, 0), name, 100, motion)

    @Test
    fun queuesOnlyWhatTheServerHasNotGot() = runTest {
        val here = asset(1)
        val there = asset(2)
        database.migrate()
        database.cacheFor("instance-a").record(RemoteEntry(layout.pathFor(there), null, null))

        val queue = queue()
        assertEquals(1, queue.enqueue(listOf(here, there)))
        assertEquals(listOf(layout.pathFor(here)), queue.outstanding().map { it.path })
    }

    @Test
    fun queuesBothHalvesOfALivePhoto() = runTest {
        // Half a Live Photo is worse than neither, so both are work.
        val live = asset(3, motion = MotionPart("IMG_3.MOV", 50))
        val queue = queue()
        assertEquals(2, queue.enqueue(listOf(live)))
        assertEquals(setOf(AssetPart.Still, AssetPart.Motion), queue.outstanding().map { it.part }.toSet())
    }

    @Test
    fun sendsThePictureSomebodyJustTookFirst() = runTest {
        // A first backup that starts in 2014 looks broken for days.
        val queue = queue()
        queue.enqueue(listOf(asset(1), asset(20), asset(10)))
        val step = queue.runNext()
        assertTrue(step is QueueStep.Uploaded, "was $step")
        assertTrue(step.path.contains("2026-09-20"), step.path)
    }

    @Test
    fun makesTheFolderBeforeWritingIntoIt() = runTest {
        // Measured against a real server: a write whose parent is missing is a
        // 409, and tus refuses to even create the upload.
        val queue = queue()
        queue.enqueue(listOf(asset(5)))
        queue.runNext()
        assertEquals(listOf("/Photos/2026/09/"), directories.made)
    }

    @Test
    fun recordsWhatArrivedAndStopsAskingForIt() = runTest {
        val queue = queue()
        queue.enqueue(listOf(asset(5)))
        queue.runNext()

        assertEquals(emptyList(), queue.outstanding())
        assertTrue(database.cacheFor("instance-a").has(layout.pathFor(asset(5))))
        assertEquals("etag", database.cacheFor("instance-a").entry(layout.pathFor(asset(5)))?.etag)
        assertEquals(QueueStep.Idle, queue.runNext())
    }

    @Test
    fun comesBackLaterAfterSomethingThatMightPass() = runTest {
        val transport = FakeTransport(answers = mutableListOf(UploadOutcome.Failed(FailureKind.Transient, "timeout")))
        val queue = queue(transport)
        queue.enqueue(listOf(asset(5)))

        val step = queue.runNext()
        assertTrue(step is QueueStep.Retrying, "was $step")
        assertEquals(30_000L, step.inMillis)

        // And nothing is attempted again until the wait is over.
        assertEquals(QueueStep.Idle, queue.runNext())
        clock += 30_000
        assertTrue(queue.runNext() is QueueStep.Retrying)
    }

    @Test
    fun waitsLongerEachTimeButNotForever() = runTest {
        val transport = FakeTransport(answers = mutableListOf(UploadOutcome.Failed(FailureKind.Transient, "5xx")))
        val queue = queue(transport)
        queue.enqueue(listOf(asset(5)))

        val waits = mutableListOf<Long>()
        repeat(10) {
            val step = queue.runNext()
            if (step is QueueStep.Retrying) waits += step.inMillis
            clock += 60 * 60_000
        }
        assertEquals(listOf(30_000L, 60_000L, 120_000L), waits.take(3))
        assertTrue(waits.all { it <= 60 * 60_000L }, "$waits")
    }

    @Test
    fun doesNotKeepAskingAServerThatSaidNo() = runTest {
        // Rejected credentials do not improve by being asked again, and asking
        // again is a failed login on a server that counts them.
        val transport = FakeTransport(
            answers = mutableListOf(UploadOutcome.Failed(FailureKind.Permanent, "rejected")),
        )
        val queue = queue(transport)
        queue.enqueue(listOf(asset(5)))

        assertTrue(queue.runNext() is QueueStep.GaveUp)
        clock += 10L * 365 * 24 * 60 * 60_000
        assertEquals(QueueStep.Idle, queue.runNext())
        // Still listed, because somebody has to be told.
        assertEquals(1, queue.outstanding().size)
        assertEquals("rejected", queue.outstanding().single().lastError)
    }

    @Test
    fun continuesFromWhereAResumableTransportStopped() = runTest {
        val transport = FakeTransport(
            resumable = true,
            answers = mutableListOf(
                UploadOutcome.Interrupted(Resume("upload-1", 40)),
                UploadOutcome.Done("etag"),
            ),
        )
        val queue = queue(transport)
        queue.enqueue(listOf(asset(5)))

        assertEquals(QueueStep.Progressed(layout.pathFor(asset(5)), 40), queue.runNext())
        assertTrue(queue.runNext() is QueueStep.Uploaded)

        // The second attempt was told where to continue, and nothing re-read the
        // forty bytes the server already had.
        assertEquals(Resume("upload-1", 40), transport.sent[1].second)
        assertEquals(listOf(0L, 40L), assets.openedFrom)
    }

    @Test
    fun startsOverWithATransportThatCannotResume() = runTest {
        val transport = FakeTransport(
            resumable = false,
            answers = mutableListOf(
                UploadOutcome.Failed(FailureKind.Transient, "dropped"),
                UploadOutcome.Done("etag"),
            ),
        )
        val queue = queue(transport)
        queue.enqueue(listOf(asset(5)))
        queue.runNext()
        clock += 60_000
        queue.runNext()

        assertEquals(listOf<Resume?>(null, null), transport.sent.map { it.second })
        assertEquals(listOf(0L, 0L), assets.openedFrom)
    }

    @Test
    fun survivesTheProcessBeingKilled() = runTest {
        // Which on iOS is the ordinary case between transfers, not an accident.
        val first = queue(
            FakeTransport(resumable = true, answers = mutableListOf(UploadOutcome.Interrupted(Resume("u", 60)))),
        )
        first.enqueue(listOf(asset(5)))
        first.runNext()

        val second = queue(FakeTransport(resumable = true, answers = mutableListOf(UploadOutcome.Done("etag"))))
        assertTrue(second.runNext() is QueueStep.Uploaded)
    }

    @Test
    fun doesNotThrowAwayProgressWhenTheRollIsScannedAgain() = runTest {
        val transport = FakeTransport(
            resumable = true,
            answers = mutableListOf(UploadOutcome.Interrupted(Resume("u", 75))),
        )
        val queue = queue(transport)
        queue.enqueue(listOf(asset(5)))
        queue.runNext()

        // A second look at the camera roll must not restart a large video.
        queue.enqueue(listOf(asset(5)))
        assertEquals(75, queue.outstanding().single().offset)
    }
}
