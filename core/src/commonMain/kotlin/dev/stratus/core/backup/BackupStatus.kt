package dev.stratus.core.backup

import dev.stratus.core.instance.Instance
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
) {
    suspend fun of(instance: Instance): BackupState {
        database.migrate()

        // Before anything else: with no camera roll to read there is nothing to
        // say about progress, and silence here is what a broken backup looks like.
        if (source.access() == MediaAccess.None) {
            return BackupState.NeedsYou(AttentionReason.TheLibraryIsNotReadable, 0)
        }

        val pending = database.pendingFor(instance.id)
        val summary = pending.summary(now())
        val journal = database.journalFor(instance.id).read()

        if (journal?.running == true) {
            return BackupState.Working(journal.uploaded, summary.total, journal.currentPath)
        }

        // Something given up on outranks anything still moving: the rest of the
        // queue draining is no comfort to the photographs that will not go.
        if (summary.givenUp > 0) {
            return BackupState.NeedsYou(
                AttentionReason.SomethingWillNotSend(pending.all().firstOrNull { it.lastError != null }?.lastError),
                summary.givenUp,
            )
        }

        if (summary.total > 0) {
            return BackupState.Waiting(
                reason = if (summary.nextAttemptAt != null) WaitingReason.ForARetry else WaitingReason.ForTheNextPass,
                left = summary.total,
                bytesLeft = summary.bytesLeft,
                until = summary.nextAttemptAt,
                lastRunAt = journal?.finishedAt ?: 0,
            )
        }

        return journal?.let { BackupState.Idle(it.finishedAt, it.uploaded) } ?: BackupState.NeverRun
    }

    /**
     * One line for several servers.
     *
     * Not an average: an instance that needs attention has to be the one shown,
     * or a backup half-broken reads as a backup working. Counts are summed, the
     * state is whichever is most urgent.
     */
    suspend fun across(instances: List<Instance>): BackupState {
        val states = instances.map { of(it) }
        if (states.isEmpty()) return BackupState.NeverRun

        states.filterIsInstance<BackupState.NeedsYou>().let { needing ->
            if (needing.isNotEmpty()) {
                return needing.first().copy(affected = needing.sumOf { it.affected })
            }
        }
        states.filterIsInstance<BackupState.Working>().let { working ->
            if (working.isNotEmpty()) {
                return BackupState.Working(
                    done = working.sumOf { it.done },
                    left = states.sumOf { leftIn(it) },
                    current = working.first().current,
                )
            }
        }
        states.filterIsInstance<BackupState.Waiting>().let { waiting ->
            if (waiting.isNotEmpty()) {
                return waiting.first().copy(
                    left = waiting.sumOf { it.left },
                    bytesLeft = waiting.sumOf { it.bytesLeft },
                    lastRunAt = waiting.maxOf { it.lastRunAt },
                )
            }
        }
        states.filterIsInstance<BackupState.Idle>().let { idle ->
            if (idle.isNotEmpty()) {
                return BackupState.Idle(idle.maxOf { it.lastRunAt }, idle.sumOf { it.uploaded })
            }
        }
        return BackupState.NeverRun
    }

    private fun leftIn(state: BackupState): Int = when (state) {
        is BackupState.Working -> state.left
        is BackupState.Waiting -> state.left
        else -> 0
    }
}
