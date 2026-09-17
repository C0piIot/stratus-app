package dev.stratus.core

import android.content.Context
import dev.stratus.core.files.AndroidFileHandoff
import dev.stratus.core.store.AndroidSecureStore
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Assembled here rather than in `:ui` so the engine, the keystore and the
 * Android file APIs stay out of the interface module entirely.
 */
fun appContainer(context: Context): AppContainer {
    val application = context.applicationContext
    return AppContainer(
        engine = { OkHttp.create() },
        secure = AndroidSecureStore(application),
        handoff = AndroidFileHandoff(application),
        databasePath = application.filesDir.resolve("backup.db").absolutePath,
    )
}
