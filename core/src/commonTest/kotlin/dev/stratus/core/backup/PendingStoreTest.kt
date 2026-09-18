package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingStoreTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val now = 1_000_000L

    private suspend fun store(): PendingStore {
        database.migrate()
        return database.pendingFor("i-1")
    }

    private suspend fun PendingStore.queue(path: String, size: Long = 100) = add(
        PendingUpload(path, "local", AssetPart.Still, size, null, takenAt = path),
    )

    @Test
    fun countsTheWholePictureInOneAnswer() = runTest {
        val pending = store()
        pending.queue("/a", size = 100)
        pending.queue("/b", size = 250)

        val summary = pending.summary(now)
        assertEquals(2, summary.total)
        assertEquals(0, summary.givenUp)
        assertEquals(350, summary.bytesLeft)
        assertNull(summary.nextAttemptAt)
        assertNull(summary.givenUpDetail)
    }

    @Test
    fun countsOnlyWhatIsStillOwedAsBytesLeft() = runTest {
        // A resumed upload owes the rest, not the whole file again.
        val pending = store()
        pending.queue("/a", size = 100)
        pending.recordProgress("/a", Resume("handle", 60))
        assertEquals(40, pending.summary(now).bytesLeft)
    }

    @Test
    fun saysWhenTheEarliestWaitingOneComesBack() = runTest {
        val pending = store()
        pending.queue("/a")
        pending.queue("/b")
        pending.recordFailure("/a", "timeout", now + 90_000)
        pending.recordFailure("/b", "timeout", now + 30_000)

        assertEquals(now + 30_000, pending.summary(now).nextAttemptAt)
        // Once that moment passes, something is runnable and nothing is waiting.
        assertNull(pending.summary(now + 60_000).nextAttemptAt)
    }

    @Test
    fun carriesTheReasonBesideTheCountRatherThanMakingSomebodyGoAndLookForIt() = runTest {
        // The whole point of #48: a screen wants one sentence, and it used to
        // read every outstanding row to find it.
        val pending = store()
        pending.queue("/a")
        pending.recordFailure("/a", "the server rejected these credentials", Long.MAX_VALUE)

        val summary = pending.summary(now)
        assertEquals(1, summary.givenUp)
        assertEquals("the server rejected these credentials", summary.givenUpDetail)
    }

    @Test
    fun handsBackFailuresBoundedAndNothingElse() = runTest {
        val pending = store()
        repeat(50) { pending.queue("/gone-$it") }
        repeat(10) { pending.queue("/waiting-$it") }
        repeat(50) { pending.recordFailure("/gone-$it", "forbidden", Long.MAX_VALUE) }
        repeat(10) { pending.recordFailure("/waiting-$it", "timeout", now + 60_000) }

        val shown = pending.failures(limit = 5)
        assertEquals(5, shown.size, "the limit is what stops a screen reading the whole queue")
        assertTrue(shown.all { it.path.startsWith("/gone-") }, shown.map { it.path }.toString())
        // Something merely waiting is not a failure and must not be listed as one.
        assertEquals(50, pending.summary(now).givenUp)
    }
}
