package dev.stratus.core.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.io.File

/**
 * Opening hands the file to whatever can show it; saving puts a copy in
 * Downloads, where somebody will look for it.
 *
 * The two are genuinely different on Android, which is why they are two methods
 * and not one with a flag.
 */
class AndroidFileHandoff(private val context: Context) : FileHandoff {

    override suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
        val file = File(context.cacheDir, "handoff").apply { mkdirs() }.resolve(name.sanitised())
        writeTo(Path(file.absolutePath), body)

        // A content URI through a provider, because a file:// URI has been
        // refused since Android 7 and the failure is a crash in the other app.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, contentType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit) =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name.sanitised())
                if (contentType != null) put(MediaStore.Downloads.MIME_TYPE, contentType)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            ) { "Downloads refused a new file" }
            resolver.openOutputStream(uri).use { stream ->
                requireNotNull(stream) { "Downloads gave nothing to write to" }
                body(stream.asSink().buffered())
            }
        }

    private suspend fun writeTo(path: Path, body: suspend (Sink) -> Unit) =
        withContext(Dispatchers.IO) {
            SystemFileSystem.sink(path).buffered().use { body(it) }
        }

    /** A name off a server is not a path, and must not become one here. */
    private fun String.sanitised(): String = substringAfterLast('/').ifEmpty { "download" }
}
