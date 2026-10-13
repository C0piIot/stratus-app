package dev.stratus.core.media

import dev.stratus.core.backup.Connections
import dev.stratus.core.server.ServerStore
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** What the server has to say when it is asked for a picture of a file. */
sealed interface Thumbnail {
    /** The bytes, which are a JPEG whatever the file was. */
    class Picture(val bytes: ByteArray) : Thumbnail

    /** There is none and asking again will not change that. */
    data object None : Thumbnail

    /**
     * The server is making as many as it can right now, and [afterMs] is how
     * long it asked to be left alone for.
     *
     * The whole reason this type exists: null used to mean this as well as
     * [None], so a refusal became an icon that never became a picture
     * (stratus-app#130).
     */
    data class NotNow(val afterMs: Long) : Thumbnail
}

/**
 * The server's own rendering of a file, asked for a few at a time.
 *
 * One per process, because the budget is the server's rather than any one
 * screen's: the picker and -- when it has them -- the app's own listing queue
 * behind the same [atOnce] permits and sit out the same refusal.
 *
 * Two things it is careful about, and both are stratus-app#130:
 *
 * - **It bounds what this app puts on the wire.** A picker binds forty rows and
 *   asks about each, which against a one-CPU server is thirty-five refusals to
 *   learn what one of them said. The number is about being a reasonable client
 *   of any WebDAV server rather than a guess at this one's capacity.
 * - **A `503` closes the door for as long as it asked.** While it is closed
 *   nothing is sent at all, so one refusal costs one request rather than one
 *   per row.
 *
 * It is the one piece here that knows it may be talking to a Stratus, and it
 * costs nothing to be wrong about: `/thumb/` on anything else answers something
 * that is not an image, which is [Thumbnail.None] and an icon. `Retry-After` is
 * ordinary HTTP and adds no Stratus-only knowledge beyond that address.
 */
class Thumbnails(
    private val instances: ServerStore,
    private val connections: Connections,
    atOnce: Int = AT_ONCE,
    private val now: () -> Long = { getTimeMillis() },
) {
    private val slots = Semaphore(atOnce)
    private val lock = Mutex()
    private var closedUntil = 0L

    /** [path] is as WebDAV gives it, leading slash and all. */
    suspend fun of(path: String, width: Int): Thumbnail {
        left()?.let { return it }
        val instance = instances.instance() ?: return Thumbnail.None
        val credentials = instances.credentials() ?: return Thumbnail.None
        val connection = connections.to(instance) ?: return Thumbnail.None
        val url = ShareLinks(instance.baseUrl, credentials)
            .thumbnail(path, width, ShareLife.ADay, now() / 1000) ?: return Thumbnail.None
        return slots.withPermit {
            // Asked again in here because waiting for a permit is where most of
            // a bound grid's requests spend their time, and the door may have
            // shut while they did.
            left() ?: fetch(connection.http, url)
        }
    }

    private suspend fun fetch(http: HttpClient, url: String): Thumbnail = try {
        val response = http.get(url)
        val type = response.headers[HttpHeaders.ContentType].orEmpty()
        when {
            response.status == HttpStatusCode.ServiceUnavailable ||
                response.status == HttpStatusCode.TooManyRequests ->
                Thumbnail.NotNow(close(waitOf(response.headers[HttpHeaders.RetryAfter])))
            response.status.isSuccess() && type.startsWith("image/") ->
                Thumbnail.Picture(response.bodyAsBytes())
            else -> Thumbnail.None
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Not an absence: nothing was learned about whether there is a picture.
        // It does not shut the door either, which is about the server being
        // full rather than about one request failing.
        Thumbnail.NotNow(DEFAULT_WAIT)
    }

    /** How long is left of a refusal, or null when there is none to serve. */
    private suspend fun left(): Thumbnail.NotNow? = lock.withLock {
        (closedUntil - now()).takeIf { it > 0 }?.let(Thumbnail::NotNow)
    }

    /**
     * Answers what is left rather than what was asked for, so that two refusals
     * at once cannot report a door shorter than the door.
     */
    private suspend fun close(wait: Long): Long = lock.withLock {
        closedUntil = maxOf(closedUntil, now() + wait)
        closedUntil - now()
    }

    /**
     * Seconds, which is what the server sends. RFC 9110 allows a date there
     * too; anything this cannot read is [DEFAULT_WAIT], and the ceiling is so
     * that a server having a bad day cannot turn pictures off for an afternoon.
     */
    private fun waitOf(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return DEFAULT_WAIT
        return (seconds * 1000).coerceIn(1_000, LONGEST_WAIT)
    }

    companion object {
        const val AT_ONCE = 4
        private const val DEFAULT_WAIT = 5_000L
        private const val LONGEST_WAIT = 30_000L
    }
}
