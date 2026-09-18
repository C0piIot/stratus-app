package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import kotlinx.io.RawSource

/**
 * A plain WebDAV `PUT`, which every server has and none of them can resume.
 *
 * The baseline the app always falls back to. An interruption here is a failure
 * and not a pause: the server will admit to nothing it received, so the next
 * attempt starts from the beginning. That is precisely the cost tus exists to
 * remove, and why the queue above can express resuming even though this cannot.
 */
class PutTransport(private val dav: DavClient) : Transport {

    override val resumable = false

    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        open: suspend (from: Long) -> RawSource,
    ): UploadOutcome =
        try {
            UploadOutcome.Done(dav.put(target.path, target.size, open(0), target.contentType))
        } catch (e: DavError) {
            UploadOutcome.Failed(kindOf(e), e.message ?: "no detail")
        }

    /**
     * Whether waiting helps.
     *
     * Rejected credentials and a forbidden path do not improve by being asked
     * again, and asking again costs a lockout on a server that counts failed
     * logins. A conflict usually means the folder is not there, which the queue
     * makes before every attempt, so it is worth one more try.
     */
    private fun kindOf(error: DavError): FailureKind = when (error) {
        is DavError.Unauthorized, is DavError.Forbidden -> FailureKind.Permanent
        else -> FailureKind.Transient
    }
}

/** Makes the month folder, and the year above it, before anything is written there. */
class DavDirectoryMaker(private val dav: DavClient) : DirectoryMaker {
    override suspend fun ensure(path: String) {
        var at = ""
        for (part in path.trim('/').split('/')) {
            at += "/$part"
            try {
                dav.makeCollection(at)
            } catch (_: DavError.Conflict) {
                // Already there, which is the ordinary case after the first photo.
            }
        }
    }
}
