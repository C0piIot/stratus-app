package dev.stratus.core.documents

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RangeReaderTest {

    private val bytes = ByteArray(10_000) { (it % 251).toByte() }
    private val opens = mutableListOf<Long>()

    private val jobs = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + jobs)

    @AfterTest
    fun stop() = jobs.cancel()

    private fun reader() = RangeReader(scope, bytes.size.toLong()) { from, use ->
        opens += from
        use(ByteReadChannel(bytes.copyOfRange(from.toInt(), bytes.size)))
    }

    @Test
    fun aRunOfSequentialReadsCostsOneRequest() = runTest {
        // The whole reason this class exists. A player asking for four
        // kilobytes at a time would otherwise be one HTTP request per four
        // kilobytes, which is worse than downloading the file.
        val reader = reader()
        var at = 0L
        repeat(10) {
            val piece = reader.read(at, 500)
            assertContentEquals(bytes.copyOfRange(at.toInt(), at.toInt() + 500), piece)
            at += piece.size
        }
        assertEquals(listOf(0L), opens, "a sequential run opened more than once")
        reader.close()
    }

    @Test
    fun aSeekOpensAgainAtTheNewPlace() = runTest {
        val reader = reader()
        reader.read(0, 100)
        reader.read(5_000, 100).let { assertContentEquals(bytes.copyOfRange(5_000, 5_100), it) }
        // And carries on sequentially from there without opening a third time.
        reader.read(5_100, 100).let { assertContentEquals(bytes.copyOfRange(5_100, 5_200), it) }
        assertEquals(listOf(0L, 5_000L), opens)
        reader.close()
    }

    @Test
    fun theEndOfTheFileIsShortAndNotAnError() = runTest {
        val reader = reader()
        val tail = reader.read(bytes.size - 10L, 4_096)
        assertEquals(10, tail.size)
        assertTrue(reader.read(bytes.size.toLong(), 4_096).isEmpty(), "past the end is nothing at all")
        reader.close()
    }

    @Test
    fun aFailureReachesTheCallerAndDoesNotKillTheReader() = runTest {
        // A dropped connection has to be one failed read rather than a file
        // that can never be read again.
        var fail = true
        val reader = RangeReader(scope, bytes.size.toLong()) { from, use ->
            if (fail) throw kotlinx.io.IOException("the network went away")
            use(ByteReadChannel(bytes.copyOfRange(from.toInt(), bytes.size)))
        }
        runCatching { reader.read(0, 100) }.exceptionOrNull().let {
            assertTrue(it is kotlinx.io.IOException, "was $it")
        }
        fail = false
        assertContentEquals(bytes.copyOfRange(0, 100), reader.read(0, 100))
        reader.close()
    }
}
