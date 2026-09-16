package dev.stratus.core.signin

import dev.stratus.core.net.DavProber
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.CredentialStore
import dev.stratus.core.store.KeychainSecureStore
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope

/** Builds the controller with everything iOS supplies. See the Android twin. */
fun signInController(scope: CoroutineScope): SignInController {
    val secure = KeychainSecureStore()
    return SignInController(
        prober = DavProber { credentials -> stratusHttpClient(Darwin.create(), credentials) },
        credentials = CredentialStore(secure),
        consent = ConsentStore(secure),
        scope = scope,
    )
}
