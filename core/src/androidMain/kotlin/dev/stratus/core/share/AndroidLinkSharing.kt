package dev.stratus.core.share

import android.content.Context
import android.content.Intent

/** The system's own sheet, which is where a link is expected to come from. */
class AndroidLinkSharing(private val context: Context) : LinkSharing {

    override suspend fun offer(link: String, name: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, link)
            // What the sheet's preview calls it; the link itself is the payload.
            .putExtra(Intent.EXTRA_TITLE, name)
        context.startActivity(
            // NEW_TASK because this starts from an application context, the same
            // reason the file handoff needs it.
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
