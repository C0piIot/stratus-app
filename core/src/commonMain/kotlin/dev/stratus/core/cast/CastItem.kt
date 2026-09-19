package dev.stratus.core.cast

import dev.stratus.core.dav.DavResource
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks

/** A URL a television can fetch, and what it will find there. */
data class CastItem(val url: String, val contentType: String, val title: String)

/**
 * What to send a Chromecast for a file, or null when there is nothing it could
 * do with it.
 *
 * The receiver fetches the URL itself and cannot send credentials, so every one
 * of these is a signed link -- and a link that lives **a day**, because a cast
 * is a session and nothing on the other end keeps it.
 *
 * A photograph is sent as the server's rendering of it rather than as itself.
 * Google's default receiver reads JPEG, PNG and H.264 and no more, so a HEIC --
 * which is what an iPhone records and what this app uploads untouched -- would
 * show nothing at all. `/thumb/` already turns one into a JPEG, and asking for
 * the large size gets a picture worth putting on a television.
 *
 * Video goes as it is. There is no transcoding at either end, so H.264 plays and
 * an iPhone's HEVC will not unless the device is a 4K one -- see
 * stratus-backend#50. Sending it anyway is right: the failure is the television's
 * to report, and refusing here would also refuse everything that does work.
 */
fun castItemFor(entry: DavResource, links: ShareLinks, nowEpochSeconds: Long): CastItem? {
    if (entry.isDirectory) return null
    val kind = entry.contentType?.substringBefore('/') ?: return null
    return when (kind) {
        "image" -> CastItem(
            url = links.thumbnail(entry.path, TELEVISION_WIDTH, ShareLife.ADay, nowEpochSeconds),
            contentType = "image/jpeg",
            title = entry.name,
        )

        "video", "audio" -> CastItem(
            url = links.link(entry.path, isDirectory = false, life = ShareLife.ADay, nowEpochSeconds = nowEpochSeconds),
            contentType = entry.contentType,
            title = entry.name,
        )

        else -> null
    }
}

/** The larger of the two sizes the server makes, and the only one worth a screen. */
private const val TELEVISION_WIDTH = 1200
