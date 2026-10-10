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
        body: UploadBody,
    ): UploadOutcome =
        try {
            UploadOutcome.Done(dav.put(target.path, target.size, body.open(0), target.contentType))
        } catch (e: DavError) {
            UploadOutcome.Failed(kindOf(e), e.message ?: "no detail")
        }
}

/**
 * Whether waiting helps.
 *
 * Rejected credentials and a forbidden path do not improve by being asked
 * again, and asking again costs a lockout on a server that counts failed
 * logins. A conflict usually means the folder is not there, which the queue
 * makes before every attempt, so it is worth one more try.
 *
 * Out here rather than inside the transport because the queue weighs a refused
 * *folder* by the same rule, and two copies of this would drift.
 */
internal fun kindOf(error: DavError): FailureKind = when (error) {
    is DavError.Unauthorized, is DavError.Forbidden -> FailureKind.Permanent
    else -> FailureKind.Transient
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
 *
 * **And once per pass, not once per photograph.** One request in the ordinary
 * case is still fifty-four requests for one folder across a pass, every one of
 * them a chance to time out on a bad connection -- and a timed-out `MKCOL` used
 * to be charged to the photograph behind it, which is how six files ended up
 * serving out a thirty-two minute backoff for a folder forty-eight uploads had
 * already landed in (stratus-app#134). [Backup.prepare] builds one of these per
 * pass, so the set's lifetime is the pass and there is nothing to expire.
 *
 * Only what was made goes in. A folder deleted on the server between two
 * photographs of the same minute is a `Conflict` on the upload and an ordinary
 * retry, and the next pass starts with an empty set.
 */
class DavDirectoryMaker(private val dav: DavClient) : DirectoryMaker {
    private val made = mutableSetOf<String>()

    override suspend fun ensure(path: String) {
        val at = "/" + path.trim('/')
        if (at == "/" || at in made) return
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
        // Only here: every path above that did not throw leaves the folder there.
        made += at
    }
}
