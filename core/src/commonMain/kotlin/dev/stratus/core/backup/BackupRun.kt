package dev.stratus.core.backup

import dev.stratus.core.server.Server
import kotlinx.coroutines.CancellationException

/** What a pass over the camera roll did, for whoever has to report it. */
data class BackupReport(
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
    private val prepareFor: suspend () -> ServerBackup?,
    private val journalFor: suspend () -> BackupJournal,
    private val now: () -> Long = { io.ktor.util.date.getTimeMillis() },
    /**
     * What this pass says about itself, for the one address that is narrated.
     *
     * A seam rather than a constructor call so a test can read the lines --
     * which is the only way to prove the half that matters, that every other
     * address gets none.
     */
    private val logFor: (Server) -> BackupLog = { BackupLog(it.baseUrl) },
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
        instance: Server,
        keepGoing: () -> Boolean = { true },
        onStep: (QueueStep) -> Unit = {},
    ): BackupReport {
        val journal = journalFor()
        val log = logFor(instance)
        if (source.access() == MediaAccess.None) {
            val report = BackupReport(stopped = StoppedBecause.NoAccessToTheLibrary)
            journal.ended(now(), report.stopped, 0, 0)
            log.summarise("pass: the library cannot be read")
            return report
        }
        val prepared = prepareFor() ?: return BackupReport()
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
            val report = BackupReport(stopped = StoppedBecause.WaitingToRetry)
            journal.ended(now(), report.stopped, 0, 0)
            log.summarise("pass: could not ask the server what it already has; waiting")
            return report
        }

        var report = BackupReport(queued = queue.enqueue(assets))
        // Seen against queued is the whole diagnosis: a pass that enumerates
        // forty photographs and queues none of them is working, and one that
        // queues the same forty every time is sending them again.
        if (log.on) log.say("pass began: ${assets.size} seen, ${report.queued} queued, ${queue.left()} to send")

        while (keepGoing()) {
            val step = queue.runNext()
            onStep(step)
            narrate(log, step, queue)
            report = when (step) {
                is QueueStep.Idle -> {
                    val done = report.copy(stopped = stoppedFrom(queue))
                    advance(prepared.mark, queue, assets)
                    journal.ended(now(), done.stopped, done.uploaded, done.failed)
                    narrateEnd(log, done, queue)
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
        narrateEnd(log, stopped, queue)
        return stopped
    }

    /**
     * One line per file, naming it and saying how much is behind it.
     *
     * The count is asked of the queue rather than carried along, because a
     * number kept beside the table is a number that eventually disagrees with
     * it -- and a log that disagrees with the screen is worse than no log. It
     * costs one query per file, paid only where the log is on.
     */
    private suspend fun narrate(log: BackupLog, step: QueueStep, queue: UploadQueue) {
        if (!log.on) return
        val left = queue.left()
        log.say(
            when (step) {
                is QueueStep.Idle -> return
                is QueueStep.Uploaded -> "sent ${step.path} -- $left left"
                is QueueStep.Progressed -> "part of ${step.path}, at ${step.offset} -- $left left"
                is QueueStep.HandedOver -> "handed ${step.path} to the system -- $left left"
                is QueueStep.Retrying -> "retrying ${step.path} in ${step.inMillis}ms (${step.detail}) -- $left left"
                is QueueStep.GaveUp -> "gave up on ${step.path} (${step.detail}) -- $left left"
            },
        )
    }

    /** The line the rest of the pass hangs from, and on Android the event. */
    private suspend fun narrateEnd(log: BackupLog, report: BackupReport, queue: UploadQueue) {
        if (!log.on) return
        log.summarise(
            "pass ended ${report.stopped}: ${report.uploaded} sent, " +
                "${report.failed} failed, ${queue.left()} left",
        )
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
