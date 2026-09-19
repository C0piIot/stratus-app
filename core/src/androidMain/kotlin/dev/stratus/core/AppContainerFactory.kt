package dev.stratus.core

import android.content.Context
import dev.stratus.core.backup.AndroidAssetSource
import dev.stratus.core.files.AndroidFileHandoff
import dev.stratus.core.net.PinnedHostnameVerifier
import dev.stratus.core.net.PinningTrustManager
import dev.stratus.core.net.pinnedSocketFactory
import dev.stratus.core.share.AndroidLinkSharing
import dev.stratus.core.store.AndroidSecureStore
import io.ktor.client.engine.okhttp.OkHttp
import javax.net.ssl.HttpsURLConnection

/**
 * Assembled here rather than in `:ui` so the engine, the keystore and the
 * Android file APIs stay out of the interface module entirely.
 */
fun appContainer(context: Context): AppContainer {
    val application = context.applicationContext
    return AppContainer(
        engine = { policy ->
            OkHttp.create {
                // Both halves, because a home server usually fails both checks:
                // nothing vouches for the certificate *and* the name on it is
                // wrong, and only the second one reports through the verifier.
                val trust = PinningTrustManager(policy)
                config {
                    sslSocketFactory(pinnedSocketFactory(trust), trust)
                    hostnameVerifier(
                        PinnedHostnameVerifier(policy, HttpsURLConnection.getDefaultHostnameVerifier()),
                    )
                }
            }
        },
        secure = AndroidSecureStore(application),
        handoff = AndroidFileHandoff(application),
        sharing = AndroidLinkSharing(application),
        databasePath = application.filesDir.resolve("backup.db").absolutePath,
        assets = AndroidAssetSource(application),
    )
}
