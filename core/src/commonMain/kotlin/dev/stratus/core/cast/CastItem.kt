package dev.stratus.core.cast

import dev.stratus.core.dav.DavResource
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks

/**
 * A URL a television can fetch, and what it will find there -- and, for a film,
 * the file itself to fall back on when the server does not offer it as HLS.
 */
data class CastItem(
    val url: String,
    val contentType: String,
    val title: String,
    val fallback: CastItem? = null,
)

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
 * A film is sent as HLS, `?hls=index.m3u8` on the same link, which Stratus
 * remuxes -- and re-encodes, where the picture needs it -- as it is watched
 * (stratus-backend#50). Always, and not only for what a Chromecast cannot take:
 * WebDAV says nothing about codecs, so the app cannot tell an iPhone's HEVC,
 * which most of them do not decode, from an H.264 they do, and the master
 * playlist declares both for the receiver to choose between. The file itself is
 * the fallback, for a server that does not do HLS, and there a film the
 * television cannot decode is the television's to report.
 *
 * Audio goes as it is: every receiver decodes what a phone records.
 */
fun castItemFor(entry: DavResource, links: ShareLinks, nowEpochSeconds: Long): CastItem? {
    if (entry.isDirectory) return null
    val kind = entry.contentType?.substringBefore('/') ?: return null
    return when (kind) {
        "image" -> CastItem(
            // Null for a path the server cannot sign for, which is anything
            // outside the files collection: a receiver fetches by URL and the
            // only credential it can carry is the signature.
            url = links.thumbnail(entry.path, TELEVISION_WIDTH, ShareLife.ADay, nowEpochSeconds) ?: return null,
            contentType = "image/jpeg",
            title = entry.name,
        )

        "video", "audio" -> {
            val direct = CastItem(
                url = links.link(entry.path, isDirectory = false, life = ShareLife.ADay, nowEpochSeconds = nowEpochSeconds)
                    ?: return null,
                contentType = entry.contentType,
                title = entry.name,
            )
            if (kind == "audio") {
                direct
            } else {
                CastItem(url = "${direct.url}&$HLS_PLAYLIST", contentType = HLS_TYPE, title = entry.name, fallback = direct)
            }
        }

        else -> null
    }
}

/** The larger of the two sizes the server makes, and the only one worth a screen. */
private const val TELEVISION_WIDTH = 1200

private const val HLS_PLAYLIST = "hls=index.m3u8"

/** What Stratus sends a playlist as, and what a receiver is told to expect. */
const val HLS_TYPE = "application/vnd.apple.mpegurl"
