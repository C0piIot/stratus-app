package dev.stratus.core.backup

import dev.stratus.core.instance.Instance

/** What a pass over the camera roll did, for whoever has to report it. */
data class BackupReport(
    val instanceId: String,
    val queued: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val stopped: StoppedBecause = StoppedBecause.NothingLeft,
)

/** Why a pass ended, which is the difference between finished and interrupted. */
enum class StoppedBecause {
    NothingLeft,

    /** Everything outstanding is waiting out a backoff. Not a failure. */
    WaitingToRetry,

    /** Whoever was driving asked it to stop -- the system reclaiming the app, usually. */
    AskedTo,

    /** The library is not readable, so there is nothing to look at. */
    NoAccessToTheLibrary,
}

/**
 * One pass: look at the camera roll, queue what is missing, send what is queued.
 *
 * Lives here rather than in a platform's scheduler because it is the part that
 * can be tested. What Android and iOS contribute is *when* this runs and how to
 * stay alive while it does -- neither of which is a decision about backing up.
 */
class BackupRun(
    private val source: AssetSource,
    private val queueFor: suspend (Instance) -> UploadQueue?,
    private val journalFor: suspend (Instance) -> BackupJournal,
    private val now: () -> Long = { io.ktor.util.date.getTimeMillis() },
) {
    /**
     * Runs one pass for [instance].
     *
     * [keepGoing] is asked before every upload so the caller can stop at a clean
     * point: on a phone the answer becomes false when the system wants the app
     * back, and stopping between files loses nothing because the queue is in the
     * database.
     */
    suspend fun once(
        instance: Instance,
        keepGoing: () -> Boolean = { true },
        onStep: (QueueStep) -> Unit = {},
    ): BackupReport {
        val journal = journalFor(instance)
        if (source.access() == MediaAccess.None) {
            val report = BackupReport(instance.id, stopped = StoppedBecause.NoAccessToTheLibrary)
            journal.ended(now(), report.stopped, 0, 0)
            return report
        }
        val queue = queueFor(instance) ?: return BackupReport(instance.id)

        journal.began(now())
        val assets = source.assets(instance.sources)
        var report = BackupReport(instance.id, queued = queue.enqueue(assets))

        while (keepGoing()) {
            val step = queue.runNext()
            onStep(step)
            report = when (step) {
                is QueueStep.Idle -> {
                    val done = report.copy(stopped = stoppedFrom(queue))
                    journal.ended(now(), done.stopped, done.uploaded, done.failed)
                    return done
                }
                is QueueStep.Uploaded -> report.copy(uploaded = report.uploaded + 1)
                is QueueStep.GaveUp -> report.copy(failed = report.failed + 1)
                // A retry or a partial upload is neither done nor lost: the row
                // stays, and this pass simply moves on to whatever is next.
                is QueueStep.Retrying, is QueueStep.Progressed -> report
            }
            // Written per file so a screen reopened mid-backup says what is
            // happening rather than what was happening when it last looked.
            journal.sending((step as? QueueStep.Uploaded)?.path)
        }
        val stopped = report.copy(stopped = StoppedBecause.AskedTo)
        journal.ended(now(), stopped.stopped, stopped.uploaded, stopped.failed)
        return stopped
    }

    /**
     * Idle means nothing is *runnable*, which is not the same as nothing being
     * left: work waiting out a backoff is still work, and a screen that said
     * "finished" over it would be lying.
     */
    private suspend fun stoppedFrom(queue: UploadQueue): StoppedBecause =
        if (queue.left() == 0) StoppedBecause.NothingLeft else StoppedBecause.WaitingToRetry
}
