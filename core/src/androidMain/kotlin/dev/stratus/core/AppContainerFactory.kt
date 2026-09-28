package dev.stratus.core

import android.content.Context
import dev.stratus.core.backup.AndroidAssetSource
import dev.stratus.core.cast.AndroidCaster
import dev.stratus.core.cast.Caster
import dev.stratus.core.cast.NoCaster
import dev.stratus.core.files.AndroidFileHandoff
import dev.stratus.core.net.PinnedHostnameVerifier
import dev.stratus.core.net.PinningTrustManager
import dev.stratus.core.net.pinnedSocketFactory
import dev.stratus.core.share.AndroidLinkSharing
import dev.stratus.core.store.AndroidSecureStore
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.ktor.client.engine.okhttp.OkHttp
import javax.net.ssl.HttpsURLConnection

@Volatile private var shared: AppContainer? = null

/**
 * Assembled here rather than in `:ui` so the engine, the keystore and the
 * Android file APIs stay out of the interface module entirely.
 *
 * One per process, because the application, the activity and the backup worker
 * all ask: three containers were three connections to one database file, and
 * the status poll met the worker's writes as `database is locked`
 * (STRATUS-APP-1). The single-thread dispatcher in
 * [dev.stratus.core.backup.BackupDatabase] only serialises one connection.
 */
fun appContainer(context: Context): AppContainer =
    shared ?: synchronized(AppContainer::class) {
        shared ?: build(context.applicationContext).also { shared = it }
    }

private fun build(application: Context): AppContainer {
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
        caster = casterFor(application),
        databasePath = application.filesDir.resolve("backup.db").absolutePath,
        assets = AndroidAssetSource(application),
    )
}

/**
 * Casting where the phone can, and the whole app minus the button where it
 * cannot.
 *
 * The Cast SDK lives inside Google Play Services. A phone without them -- a
 * de-Googled ROM, a Huawei -- installs and runs this perfectly well, and asking
 * before touching `CastContext` is all it takes: the alternative is a crash on
 * exactly the devices whose owners chose them on purpose.
 */
fun casterFor(context: Context): Caster =
    if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
        AndroidCaster(context)
    } else {
        NoCaster()
    }
