package dev.stratus.core.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/**
 * Both verbs end at the share sheet, and that is iOS rather than laziness.
 *
 * There is no general filesystem to put a download in and no Downloads folder to
 * look in afterwards; "Save to Files" is an entry in the same sheet that offers
 * to open the file in something. Presenting one thing for both is what the
 * platform actually offers.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileHandoff : FileHandoff {

    override suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit) =
        present(name, body)

    override suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit) =
        present(name, body)

    private suspend fun present(name: String, body: suspend (Sink) -> Unit) {
        val path = Path(NSTemporaryDirectory() + name.substringAfterLast('/').ifEmpty { "download" })
        SystemFileSystem.sink(path).buffered().use { body(it) }

        val sheet = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(path.toString())),
            applicationActivities = null,
        )
        // keyWindow is deprecated and still the only one-liner that works across
        // the versions this app supports; a scene walk buys nothing here.
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(sheet, animated = true, completion = null)
    }
}
