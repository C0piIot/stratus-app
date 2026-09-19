package dev.stratus.core.share

import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/** The same sheet the file handoff presents, with a URL in it instead of a file. */
class IosLinkSharing : LinkSharing {

    override suspend fun offer(link: String, name: String) {
        // A URL rather than the string: the sheet then offers Messages, Mail and
        // Copy as a link rather than as a paragraph of text.
        val item = NSURL.URLWithString(link) ?: return
        val sheet = UIActivityViewController(activityItems = listOf(item), applicationActivities = null)
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(sheet, animated = true, completion = null)
    }
}
