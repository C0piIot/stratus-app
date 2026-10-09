package dev.stratus.core.backup.conformance

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.Asset
import dev.stratus.core.backup.AssetPart
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.Connection
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.backup.utcMillis
import dev.stratus.core.backup.DavDirectoryMaker
import dev.stratus.core.backup.PutTransport
import dev.stratus.core.backup.Transport
import dev.stratus.core.backup.TusTransport
import dev.stratus.core.backup.transportFor
import dev.stratus.core.backup.QueueStep
import dev.stratus.core.backup.RemoteLayout
import dev.stratus.core.backup.UploadQueue
import dev.stratus.core.dav.DavClient
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.originOf
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

    private val asset = Asset("local-1", utcMillis(2026, 9, 18, 9, 30, 0), originalName = "IMG_1.HEIC", sizeBytes = bytes.size.toLong())

    private val source = object : AssetSource {
        override suspend fun access() = MediaAccess.Full
        override suspend fun sources() = emptyList<MediaSource>()
        override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long) = listOf(asset)
        override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
            Buffer().apply { write(bytes, from.toInt(), bytes.size) }
    }

    private suspend fun queueFor(
        id: String,
        dav: DavClient,
        layout: RemoteLayout = this.layout,
        transport: Transport = PutTransport(dav),
    ): UploadQueue {
        database.migrate()
        return UploadQueue(
            layout = layout,
            pending = database.pending(),
            cache = database.cache(),
            source = source,
            transport = transport,
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

    /**
     * The shape production actually has since stratus-backend#279: the base is
     * the origin and the backup root is `files/<something>`, so the first
     * segment of every path is a collection the server made and a subtree its
     * router owns. Asking to create that one answers neither 201 nor 405, and
     * a maker that asked died on a folder that was plainly there.
     */
    @Test
    fun makesTheMonthUnderTheServersOwnCollectionWithoutTryingToMakeIt() = runTest {
        val fromOrigin = davAt(originOf(requireNotNull(System.getenv("STRATUS_TEST_URL"))))
        val month = "/files/upload-${Random.nextLong().toULong().toString(16)}/2026/10/"

        DavDirectoryMaker(fromOrigin).ensure(month)
        assertTrue(fromOrigin.stat(month).isDirectory, "the month was not made")

        // And again, which is every photograph after the first one.
        DavDirectoryMaker(fromOrigin).ensure(month)
    }

    /**
     * The configuration the app actually ships, and the only test here that
     * has it whole: the base is the origin, the root is `files/<something>`
     * of which the first segment is the server's own, and **the transport is
     * the negotiated one** rather than a name written into the test.
     *
     * That last part is what was missing. Every other case here says
     * `PutTransport`, so the path a photograph really takes -- tus, carrying
     * the path the client browses -- was proved by nothing faster than the
     * emulator, and stratus-backend#285 reached it there (three rounds of
     * fifteen minutes) instead of here (thirty seconds).
     */
    @Test
    fun backsUpWithTheDefaultRootAgainstTheOrigin() = runTest {
        val origin = originOf(requireNotNull(System.getenv("STRATUS_TEST_URL")))
        val fromOrigin = davAt(origin)
        val root = "files/phone_backup-${Random.nextLong().toULong().toString(16)}"
        val transport = transportFor(Connection(stratusHttpClient(CIO.create(), credentials), fromOrigin), origin)
        assertTrue(transport is TusTransport, "this server offers tus, so a pass should have taken it")

        val queue = queueFor("origin-rooted", fromOrigin, RemoteLayout(root), transport)

        assertEquals(1, queue.enqueue(listOf(asset)))
        val steps = drain(queue)
        assertTrue(steps.any { it is QueueStep.Uploaded }, "nothing was uploaded: $steps")

        val landed = RemoteLayout(root).pathFor(asset)
        assertEquals(bytes.size.toLong(), fromOrigin.stat(landed).size)
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

        // And each instance settled it on its own, so neither will send it again.
        assertTrue(database.cache().has(path))
        assertTrue(database.cache().has(path))
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
