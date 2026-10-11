package dev.stratus.core.backup

import io.ktor.util.date.getTimeMillis

/**
 * Assembles what to say from what is actually true.
 *
 * Everything outstanding is counted from `pending`, which is the same table the
 * queue takes work from -- so the screen cannot claim something the queue does
 * not agree with. The journal supplies only what `pending` cannot know: whether
 * a pass is happening and when the last one ended.
 */
class BackupStatus(
    private val database: BackupDatabase,
    private val source: AssetSource,
    private val now: () -> Long = { getTimeMillis() },
    /** Whether sending is stopped for wifi right now (stratus-app#133). */
    private val parked: suspend () -> Boolean = { false },
) {
    suspend fun of(): BackupState {

        // Before anything else: with no camera roll to read there is nothing to
        // say about progress, and silence here is what a broken backup looks like.
        if (source.access() == MediaAccess.None) {
            return BackupState.NeedsYou(AttentionReason.TheLibraryIsNotReadable, 0)
        }

        val pending = database.pending()
        val summary = pending.summary(now())
        val journal = database.journal().read()

        if (journal?.running == true) {
            return BackupState.Working(journal.uploaded, summary.total, journal.currentPath)
        }

        // Something given up on outranks anything still moving: the rest of the
        // queue draining is no comfort to the photographs that will not go.
        if (summary.givenUp > 0) {
            // The reason comes out of the same query as the counts. It used to
            // come from reading every outstanding row for one string, which on a
            // real camera roll was forty thousand of them every second (#48).
            return BackupState.NeedsYou(
                AttentionReason.SomethingWillNotSend(summary.givenUpDetail),
                summary.givenUp,
            )
        }

        if (summary.total > 0) {
            return BackupState.Waiting(
                // What is moving outranks what is not: telling somebody their
                // backup is "waiting for the next pass" while the system is
                // actively sending it is the wrong thing to say.
                reason = when {
                    summary.inFlight > 0 -> WaitingReason.InTheSystemsHands
                    // Above the backoff, because a row serving one out on a
                    // metered connection is not waiting on the backoff: the
                    // pass it is waiting for will park too.
                    parked() -> WaitingReason.ForWifi
                    summary.nextAttemptAt != null -> WaitingReason.ForARetry
                    else -> WaitingReason.ForTheNextPass
                },
                left = summary.total,
                bytesLeft = summary.bytesLeft,
                until = summary.nextAttemptAt,
                lastRunAt = journal?.finishedAt ?: 0,
            )
        }

        return journal?.let { BackupState.Idle(it.finishedAt, it.uploaded) } ?: BackupState.NeverRun
    }
}
