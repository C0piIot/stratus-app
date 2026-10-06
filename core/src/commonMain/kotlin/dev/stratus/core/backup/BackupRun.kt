package dev.stratus.core.backup

import dev.stratus.core.instance.Instance
import kotlinx.coroutines.CancellationException

/** What a pass over the camera roll did, for whoever has to report it. */
data class BackupReport(
    val instanceId: String,
    val queued: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    /** Given to the system during this pass, and not finished by it. */
    val handedOver: Int = 0,
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

    /**
     * Handed to the system, which will finish it when it chooses.
     *
     * Not `WaitingToRetry`, which would be a lie about something that is not
     * waiting on us at all: on iOS a transfer outlives the pass that started
     * it, and a screen saying "waiting to retry" over a backup that is
     * actively running would be the wrong thing to tell somebody
     * (stratus-app#20).
     */
    InFlight,
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
    private val prepareFor: suspend (Instance) -> InstanceBackup?,
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
        val prepared = prepareFor(instance) ?: return BackupReport(instance.id)
        val queue = prepared.queue

        journal.began(now())
        // Bounded by how far the last pass got, which is an optimisation and
        // nothing more: everything already queued is in the database and goes
        // out whatever this enumerates, and what has actually been sent is the
        // settled record's answer (stratus-app#124).
        val assets = source.assets(instance.sources, prepared.mark.read())

        // A phone that has settled nothing asks the server before it sends
        // anything, or a reinstall is somebody's whole camera roll going up
        // again (stratus-app#124). If the asking fails there is no point
        // guessing: uploading everything is the expensive mistake this is here
        // to avoid, so the pass waits and tries again rather than paying it.
        try {
            prepared.index.warmUp(assets)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val report = BackupReport(instance.id, stopped = StoppedBecause.WaitingToRetry)
            journal.ended(now(), report.stopped, 0, 0)
            return report
        }

        var report = BackupReport(instance.id, queued = queue.enqueue(assets))

        while (keepGoing()) {
            val step = queue.runNext()
            onStep(step)
            report = when (step) {
                is QueueStep.Idle -> {
                    val done = report.copy(stopped = stoppedFrom(queue))
                    advance(prepared.mark, queue, assets)
                    journal.ended(now(), done.stopped, done.uploaded, done.failed)
                    return done
                }
                is QueueStep.Uploaded -> report.copy(uploaded = report.uploaded + 1)
                is QueueStep.GaveUp -> report.copy(failed = report.failed + 1)
                // A retry, a partial upload or one the system is now carrying
                // is neither done nor lost: the row stays, and this pass simply
                // moves on to whatever is next.
                is QueueStep.Retrying, is QueueStep.Progressed -> report
                is QueueStep.HandedOver -> report.copy(handedOver = report.handedOver + 1)
            }
            // Written per file so a screen reopened mid-backup says what is
            // happening rather than what was happening when it last looked.
            journal.sending((step as? QueueStep.Uploaded)?.path)
        }
        val stopped = report.copy(stopped = StoppedBecause.AskedTo)
        advance(prepared.mark, queue, assets)
        journal.ended(now(), stopped.stopped, stopped.uploaded, stopped.failed)
        return stopped
    }

    /**
     * Moves the bound to the oldest thing still owed, or past everything seen
     * when nothing is.
     *
     * Two rules, both of which are about never skipping a photograph. It stops
     * at what is still owed rather than at the newest success, so a file that
     * keeps failing holds the bound behind it instead of being enumerated away.
     * And **one asset with no added date freezes it entirely**: that is every
     * iOS below 26, where the platform has no such thing to report and a bound
     * taken from a guess would be a photograph nobody backs up.
     *
     * What is already queued is unaffected either way. The queue is the
     * database, so a row below the bound is still sent.
     */
    private suspend fun advance(mark: BackupMark, queue: UploadQueue, assets: List<Asset>) {
        if (assets.isEmpty() || assets.any { it.addedAtEpochMs <= 0 }) return
        val owed = queue.outstanding().mapTo(mutableSetOf()) { it.localId }
        val oldestOwed = assets.filter { it.localId in owed }.minOfOrNull { it.addedAtEpochMs }
        mark.advanceTo(oldestOwed?.minus(1) ?: assets.maxOf { it.addedAtEpochMs })
    }

    /**
     * Idle means nothing is *runnable*, which is not the same as nothing being
     * left: work waiting out a backoff is still work, and a screen that said
     * "finished" over it would be lying.
     */
    private suspend fun stoppedFrom(queue: UploadQueue): StoppedBecause {
        val left = queue.summary()
        return when {
            left.total == 0 -> StoppedBecause.NothingLeft
            // Asked in this order because a pass can end with both, and what
            // somebody wants to be told is that something is moving.
            left.inFlight > 0 -> StoppedBecause.InFlight
            else -> StoppedBecause.WaitingToRetry
        }
    }
}
