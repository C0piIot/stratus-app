package dev.stratus.core.signin

import android.content.Context
import dev.stratus.core.net.DavProber
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.store.AndroidSecureStore
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.CredentialStore
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the controller with everything Android supplies.
 *
 * Assembled here rather than in `:ui` so the engine and the keystore stay out of
 * the interface module entirely -- it renders and knows nothing else.
 */
fun signInController(context: Context, scope: CoroutineScope): SignInController {
    val secure = AndroidSecureStore(context.applicationContext)
    return SignInController(
        prober = DavProber { credentials -> stratusHttpClient(OkHttp.create(), credentials) },
        credentials = CredentialStore(secure),
        consent = ConsentStore(secure),
        scope = scope,
    )
}
