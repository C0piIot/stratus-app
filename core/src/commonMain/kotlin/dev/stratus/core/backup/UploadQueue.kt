package dev.stratus.core.backup

import kotlinx.coroutines.CancellationException
import io.ktor.util.date.getTimeMillis

/** Makes a directory and whatever it hangs from, ignoring what is already there. */
fun interface DirectoryMaker {
    suspend fun ensure(path: String)
}

/** What one turn of the queue did. */
sealed interface QueueStep {
    data object Idle : QueueStep
    data class Uploaded(val path: String) : QueueStep
    data class Progressed(val path: String, val offset: Long) : QueueStep

    /** Given to the platform; the answer arrives through [UploadQueue.settle]. */
    data class HandedOver(val path: String) : QueueStep
    data class Retrying(val path: String, val inMillis: Long, val detail: String) : QueueStep
    data class GaveUp(val path: String, val detail: String) : QueueStep
}

/**
 * What to send next, and what to do about what happened.
 *
 * One instance's worth. A photograph owed to two servers is two pieces of work
 * that succeed and fail apart, so an instance that is down cannot hold up the
 * other -- which is only true if nothing here knows about more than one.
 *
 * Every decision lives in this class and none of them in the transport, because
 * the transport is the part that will be a background `URLSession` on iOS: code
 * that runs where no test can watch it should not be deciding anything.
 */
class UploadQueue(
    private val layout: RemoteLayout,
    private val pending: PendingStore,
    private val cache: BackupCache,
    private val source: AssetSource,
    private val transport: Transport,
    private val directories: DirectoryMaker,
    private val now: () -> Long = { getTimeMillis() },
) {
    /** Queues whatever the server has not got. Returns how much was added. */
    suspend fun enqueue(assets: List<Asset>): Int {
        val known = cache.paths()
        var added = 0
        for (asset in assets) {
            val parts = buildList {
                add(Part(AssetPart.Still, layout.pathFor(asset), asset.sizeBytes, asset.mimeType))
                asset.motion?.let { add(Part(AssetPart.Motion, layout.motionPathFor(asset)!!, it.sizeBytes, it.mimeType)) }
            }
            for ((part, path, size, type) in parts) {
                if (path in known) continue
                pending.add(
                    PendingUpload(
                        path = path,
                        localId = asset.localId,
                        part = part,
                        size = size,
                        contentType = type,
                        takenAt = stampOf(asset),
                    ),
                )
                added++
            }
        }
        return added
    }

    /**
     * Takes one thing off the queue and tries it.
     *
     * One at a time, deliberately: on a phone, several at once drains the battery
     * and saturates the uplink without finishing any sooner.
     */
    suspend fun runNext(): QueueStep {
        val upload = pending.next(now()) ?: return QueueStep.Idle

        // The server refuses a write whose parent does not exist -- measured, a
        // 409 -- and tus is stricter still: its destination is a path and the
        // folder has to be there before the upload is even created.
        try {
            directories.ensure(upload.path.substringBeforeLast('/') + "/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failure(upload, FailureKind.Transient, e.message ?: "could not make the folder")
        }

        // Resuming asks the transport to continue, never the local offset alone:
        // a client can believe it is somewhere the server does not agree with,
        // which is exactly what Stratus answers with a 409.
        // The handle and not the offset: a transfer cut before its first
        // acknowledged byte still has an upload on the server to ask about.
        val resume = if (transport.resumable && upload.handle != null) {
            Resume(upload.handle, upload.offset)
        } else {
            null
        }

        val outcome = try {
            transport.send(
                UploadTarget(upload.path, upload.size, upload.contentType),
                resume,
            ) { from -> source.open(upload.localId, upload.part, from) }
        } catch (e: CancellationException) {
            // The system stopping the pass is not the server refusing the file;
            // recorded as a failure it showed up in the Backup screen as one,
            // reading "Job was cancelled" (stratus-app#83).
            throw e
        } catch (e: Exception) {
            return failure(upload, FailureKind.Transient, e.message ?: e::class.simpleName ?: "no detail")
        }

        return record(upload, outcome)
    }

    /**
     * The answer to something handed over, whenever it turns up.
     *
     * A background transport cannot answer where it was asked -- it answers
     * after the app has been suspended, killed and relaunched -- so this is
     * the other door into **exactly the same decisions** [runNext] makes.
     * That it is the same code is the point: a result recorded two ways would
     * eventually disagree with itself.
     *
     * Unknown tickets are nothing, not errors. The platform may report a
     * transfer twice, and the second time there is simply no row with that
     * ticket any more.
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
     * Asked at the start of a pass, with whatever the platform says it still
     * has.
     */
    suspend fun reconcile(live: Set<String>): Int = pending.releaseExcept(live)

    /** What is outstanding, counted. */
    suspend fun summary(): PendingSummary = pending.summary(now())

    private suspend fun record(upload: PendingUpload, outcome: UploadOutcome): QueueStep =
        when (outcome) {
            is UploadOutcome.Done -> {
                cache.record(RemoteEntry(upload.path, outcome.etag, upload.size))
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

    /** Everything outstanding. Unbounded, so not for anything asked repeatedly. */
    suspend fun outstanding(): List<PendingUpload> = pending.all()

    /** How much is left, counted rather than read. */
    suspend fun left(): Int = summary().total

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

    /** Sortable, so "newest first" is an index scan rather than a decode. */
    private fun stampOf(asset: Asset): String = asset.capturedAtEpochMs.toString().padStart(14, '0')

    private companion object {
        const val FIRST_WAIT = 30_000L
        const val LONGEST_WAIT = 60 * 60_000L
        const val NEVER = Long.MAX_VALUE
    }
}

private data class Part(val part: AssetPart, val path: String, val size: Long, val type: String?)
