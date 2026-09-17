package dev.stratus.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.BackupCache
import dev.stratus.core.backup.BackupIndex
import dev.stratus.core.backup.RemoteLayout
import dev.stratus.core.dav.DavClient
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.FileHandoff
import dev.stratus.core.net.DavProber
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.signin.SignInController
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.CredentialStore
import dev.stratus.core.store.SecureStore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/**
 * Everything the app is made of, assembled where the platform pieces are known.
 *
 * One object rather than a function per screen, because each new screen would
 * otherwise be another factory taking the same three things -- and because the
 * upload queue will want the same engine and the same credentials.
 */
class AppContainer(
    private val engine: () -> HttpClientEngine,
    private val secure: SecureStore,
    private val handoff: FileHandoff,
    private val databasePath: String,
) {
    private val credentials = CredentialStore(secure)

    // Opened once and kept: SQLite does not want a connection per question, and
    // the file is a cache, so losing it costs a rebuild and nothing else.
    private val cache by lazy { BackupCache(BundledSQLiteDriver().open(databasePath)) }

    fun signIn(scope: CoroutineScope): SignInController = SignInController(
        prober = DavProber { creds -> stratusHttpClient(engine(), creds) },
        credentials = credentials,
        consent = ConsentStore(secure),
        scope = scope,
    )

    /** Null when nobody is signed in, which is the only state it can be built from. */
    suspend fun browser(scope: CoroutineScope): BrowserController? {
        val (session, stored) = credentials.load() ?: return null
        val dav = DavClient(stratusHttpClient(engine(), stored), session.baseUrl)
        return BrowserController(dav, handoff, scope)
    }

    /** Null when nobody is signed in, for the same reason as [browser]. */
    suspend fun backupIndex(): BackupIndex? {
        val (session, stored) = credentials.load() ?: return null
        cache.migrate()
        return BackupIndex(
            layout = RemoteLayout(session.backupRoot),
            cache = cache,
            dav = DavClient(stratusHttpClient(engine(), stored), session.baseUrl),
        )
    }
}
