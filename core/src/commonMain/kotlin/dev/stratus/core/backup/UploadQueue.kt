package dev.stratus.core.backup

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
                add(Triple(AssetPart.Still, layout.pathFor(asset), asset.sizeBytes))
                asset.motion?.let { add(Triple(AssetPart.Motion, layout.motionPathFor(asset)!!, it.sizeBytes)) }
            }
            for ((part, path, size) in parts) {
                if (path in known) continue
                pending.add(
                    PendingUpload(
                        path = path,
                        localId = asset.localId,
                        part = part,
                        size = size,
                        contentType = null,
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
        } catch (e: Exception) {
            return failure(upload, FailureKind.Transient, e.message ?: "could not make the folder")
        }

        // Resuming asks the transport to continue, never the local offset alone:
        // a client can believe it is somewhere the server does not agree with,
        // which is exactly what Stratus answers with a 409.
        val resume = if (transport.resumable && upload.offset > 0) {
            Resume(upload.handle, upload.offset)
        } else {
            null
        }

        val outcome = try {
            transport.send(
                UploadTarget(upload.path, upload.size, upload.contentType),
                resume,
            ) { from -> source.open(upload.localId, upload.part, from) }
        } catch (e: Exception) {
            return failure(upload, FailureKind.Transient, e.message ?: e::class.simpleName ?: "no detail")
        }

        return when (outcome) {
            is UploadOutcome.Done -> {
                cache.record(RemoteEntry(upload.path, outcome.etag, upload.size))
                pending.remove(upload.path)
                QueueStep.Uploaded(upload.path)
            }

            is UploadOutcome.Interrupted -> {
                pending.recordProgress(upload.path, outcome.resume)
                QueueStep.Progressed(upload.path, outcome.resume.offset)
            }

            is UploadOutcome.Failed -> failure(upload, outcome.kind, outcome.detail)
        }
    }

    /** Everything outstanding, for the screen that has to say what is going on. */
    suspend fun outstanding(): List<PendingUpload> = pending.all()

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
