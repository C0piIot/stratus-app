package dev.stratus.core.share

import dev.stratus.core.dav.DavResource
import io.ktor.util.date.getTimeMillis

/**
 * Where a link goes once it exists: the system's own sharing sheet.
 *
 * An interface for the usual reason -- it is `ACTION_SEND` on one platform and
 * `UIActivityViewController` on the other, and neither belongs in shared code.
 * It decides nothing: it is handed a finished link and a name to label it with.
 */
interface LinkSharing {
    suspend fun offer(link: String, name: String)
}

/** Minting a link and handing it on, which is the whole of the feature. */
class Sharing(
    private val links: ShareLinks,
    private val sheet: LinkSharing,
    private val support: LinkSupport,
    private val now: () -> Long = { getTimeMillis() / 1000 },
) {
    /** Whether this is worth offering at all, after what a server has answered. */
    val offered: Boolean get() = support.offered

    /** False when the server does not honour the link, which is worth saying. */
    suspend fun offer(target: DavResource, life: ShareLife): Boolean {
        val link = links.link(target.path, target.isDirectory, life, now())
        if (!support.honours(link)) return false
        sheet.offer(link, target.name)
        return true
    }
}
