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

/**
 * Makes the month folder, and whatever above it is missing.
 *
 * **From the deepest upwards, and that is not a style.** Walking down from the
 * top meant asking the server to create every segment of the path on every
 * photograph, including segments that are not ours to create: with the base at
 * the origin the first of them is the server's own `files/`, and a router that
 * owns that subtree answers a redirect rather than a 405 -- which is neither
 * success nor "already there", so the upload failed on a folder that was
 * plainly there.
 *
 * Asking for the month first costs one request in the ordinary case instead of
 * four, and only a 409 -- a parent that does not exist -- sends it up a level.
 */
class DavDirectoryMaker(private val dav: DavClient) : DirectoryMaker {
    override suspend fun ensure(path: String) {
        val at = "/" + path.trim('/')
        if (at == "/") return
        try {
            dav.makeCollection(at)
        } catch (_: DavError.AlreadyExists) {
            // The ordinary case after the first photograph of the month.
        } catch (e: DavError.Conflict) {
            val parent = at.substringBeforeLast('/')
            if (parent.isEmpty()) throw e
            ensure(parent)
            try {
                dav.makeCollection(at)
            } catch (_: DavError.AlreadyExists) {
                // Somebody else made it between the two calls, which is the
                // answer we wanted anyway.
            }
        }
    }
}
