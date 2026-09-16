package dev.stratus.core

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
) {
    private val credentials = CredentialStore(secure)

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
}
