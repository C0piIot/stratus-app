package dev.stratus.core.backup.conformance

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.Asset
import dev.stratus.core.backup.AssetPart
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.CaptureTime
import dev.stratus.core.backup.DavDirectoryMaker
import dev.stratus.core.backup.PutTransport
import dev.stratus.core.backup.QueueStep
import dev.stratus.core.backup.RemoteLayout
import dev.stratus.core.backup.UploadQueue
import dev.stratus.core.dav.DavClient
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real bytes, to two real servers.
 *
 * Everything else about the queue is proved with a fake transport, which is the
 * right way round -- but a fake cannot say whether a streamed body of a megabyte
 * arrives intact, nor whether two instances end up with the same photograph and
 * agree about it afterwards.
 */
class UploadConformanceTest {

    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    private fun davAt(url: String) = DavClient(stratusHttpClient(CIO.create(), credentials), url)

    private val one = davAt(
        requireNotNull(System.getenv("STRATUS_TEST_URL")) { "run this with `make conformance`" },
    )
    private val two = davAt(
        requireNotNull(System.getenv("STRATUS_TEST_URL_2")) { "run this with `make conformance`" },
    )

    private val layout = RemoteLayout("upload-${Random.nextLong().toULong().toString(16)}")
    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))

    // A megabyte, so the body goes out in chunks rather than in one write.
    private val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }

    private val asset = Asset("local-1", CaptureTime(2026, 9, 18, 9, 30, 0), "IMG_1.HEIC", bytes.size.toLong())

    private val source = object : AssetSource {
        override suspend fun assets() = listOf(asset)
        override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
            Buffer().apply { write(bytes, from.toInt(), bytes.size) }
    }

    private suspend fun queueFor(id: String, dav: DavClient): UploadQueue {
        database.migrate()
        return UploadQueue(
            layout = layout,
            pending = database.pendingFor(id),
            cache = database.cacheFor(id),
            source = source,
            transport = PutTransport(dav),
            directories = DavDirectoryMaker(dav),
        )
    }

    private suspend fun drain(queue: UploadQueue): List<QueueStep> = buildList {
        repeat(8) {
            val step = queue.runNext()
            add(step)
            if (step is QueueStep.Idle) return@buildList
        }
    }

    @Test
    fun putsTheSamePhotographOnBothServersAndAgreesAboutIt() = runTest {
        val first = queueFor("instance-one", one)
        val second = queueFor("instance-two", two)
        assertEquals(1, first.enqueue(listOf(asset)))
        assertEquals(1, second.enqueue(listOf(asset)))

        assertTrue(drain(first).any { it is QueueStep.Uploaded }, "nothing reached the first server")
        assertTrue(drain(second).any { it is QueueStep.Uploaded }, "nothing reached the second")

        val path = layout.pathFor(asset)
        // Both servers hashed what they stored, and both hashed the same thing.
        val onEach = listOf(one.stat(path).etag, two.stat(path).etag)
        assertTrue(onEach.all { !it.isNullOrEmpty() }, "a server gave no ETag")
        assertEquals(onEach[0], onEach[1])

        // And each instance's cache kept the ETag its own server gave.
        assertEquals(onEach[0], database.cacheFor("instance-one").entry(path)?.etag)
        assertEquals(onEach[1], database.cacheFor("instance-two").entry(path)?.etag)
    }

    @Test
    fun theBytesArriveUnchangedThroughAStreamedBody() = runTest {
        val queue = queueFor("instance-one", one)
        queue.enqueue(listOf(asset))
        drain(queue)

        val read = one.read(layout.pathFor(asset)) { it.readRemaining().readByteArray() }
        assertEquals(bytes.size, read.size)
        assertTrue(read.contentEquals(bytes), "a megabyte went out and came back different")
    }

    @Test
    fun makesTheMonthFolderOnAServerThatHasNeverSeenOne() = runTest {
        // The queue creates it; without that a write is a 409 and tus will not
        // even create the upload.
        val queue = queueFor("instance-two", two)
        queue.enqueue(listOf(asset))
        drain(queue)

        assertTrue(two.stat(layout.directoryFor(asset)).isDirectory)
        assertEquals(emptyList(), queue.outstanding())
    }
}
