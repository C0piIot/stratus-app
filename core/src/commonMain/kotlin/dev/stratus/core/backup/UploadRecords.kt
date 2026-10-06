package dev.stratus.core.backup

import io.ktor.util.date.getTimeMillis

/**
 * Where the result of an upload is written down, whoever brings it and
 * whenever.
 *
 * Out of [UploadQueue] because **it needs the two tables and nothing else**
 * -- no transport, no network, no server (stratus-app#20). That is what lets
 * a result be recorded by a process that was relaunched to hear it, on a
 * phone with no signal: the upload already happened, and losing it because a
 * client could not be built would be absurd.
 *
 * One object for both doors. A result recorded two ways would eventually
 * disagree with itself.
 */
class UploadRecords(
    private val pending: PendingStore,
    private val cache: BackupCache,
    private val now: () -> Long = { getTimeMillis() },
) {
    /**
     * The answer to something handed over, as the platform saw it.
     *
     * What it *means* is decided here rather than out there, because the row
     * knows how long the file is and a background executor knows nothing at
     * all after a restart.
     */
    suspend fun settle(ticket: String, answer: TransferAnswer): QueueStep {
        val upload = pending.byTicket(ticket) ?: return QueueStep.Idle
        return settle(ticket, outcomeOf(upload, answer))
    }

    /**
     * The same, already judged.
     *
     * Unknown tickets are nothing, not errors: iOS relaunches the app to
     * report and may report twice, and the second time there is simply no row
     * with that ticket any more.
     */
    suspend fun settle(ticket: String, outcome: UploadOutcome): QueueStep {
        val upload = pending.byTicket(ticket) ?: return QueueStep.Idle
        // Freed first: whatever is decided below, nobody else is carrying it.
        pending.release(upload.path)
        return record(upload, outcome)
    }

    /**
     * Hands back anything the platform has forgotten, and answers how many.
     *
     * A row in flight is skipped by [PendingStore.next], so one whose answer
     * is never coming -- the app reinstalled, the session dropped, the system
     * having quietly discarded the task -- would sit there untouched for ever.
     */
    suspend fun reconcile(live: Set<String>): Int = pending.releaseExcept(live)

    suspend fun record(upload: PendingUpload, outcome: UploadOutcome): QueueStep =
        when (outcome) {
            is UploadOutcome.Done -> {
                cache.record(upload.path)
                pending.remove(upload.path)
                QueueStep.Uploaded(upload.path)
            }

            is UploadOutcome.Interrupted -> {
                pending.recordProgress(upload.path, outcome.resume)
                QueueStep.Progressed(upload.path, outcome.resume.offset)
            }

            is UploadOutcome.HandedOver -> {
                pending.recordHandover(upload.path, outcome.ticket, outcome.resume)
                QueueStep.HandedOver(upload.path)
            }

            is UploadOutcome.Failed -> failure(upload, outcome.kind, outcome.detail)
        }

    private suspend fun failure(upload: PendingUpload, kind: FailureKind, detail: String): QueueStep =
        if (kind == FailureKind.Permanent) {
            // Rejected credentials do not improve by being asked again, and asking
            // again costs a lockout on a server that counts failed logins. It stays
            // in the list so somebody can be told, and is never picked up again.
            pending.recordFailure(upload.path, detail, NEVER)
            QueueStep.GaveUp(upload.path, detail)
        } else {
            val wait = backoff(upload.attempts + 1)
            pending.recordFailure(upload.path, detail, now() + wait)
            QueueStep.Retrying(upload.path, wait, detail)
        }

    /** Doubling from half a minute, capped at an hour: long enough to outlast an outage. */
    private fun backoff(attempt: Int): Long {
        var wait = FIRST_WAIT
        repeat(minOf(attempt, 32) - 1) { wait = minOf(wait * 2, LONGEST_WAIT) }
        return wait
    }

    private companion object {
        const val FIRST_WAIT = 30_000L
        const val LONGEST_WAIT = 60 * 60_000L
        const val NEVER = Long.MAX_VALUE
    }
}
