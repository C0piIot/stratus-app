package dev.stratus.core.signin.conformance

import dev.stratus.core.net.DavProber
import dev.stratus.core.net.stratusHttpClient
import dev.stratus.core.signin.Question
import dev.stratus.core.signin.SignInController
import dev.stratus.core.signin.SignInFailure
import dev.stratus.core.signin.Tried
import dev.stratus.core.signin.SignInForm
import dev.stratus.core.signin.SignInState
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.InMemorySecureStore
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole sign-in flow against a real stratus-backend, started by
 * `make conformance`.
 *
 * Path discovery is the part most likely to be subtly wrong, and only a server
 * that genuinely serves `/dav/` can say whether it works. The container speaks
 * http, so this also exercises the plaintext question end to end.
 */
class SignInConformanceTest {

    private val url: String = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "STRATUS_TEST_URL is unset. Run this with `make conformance`, not directly."
    }
    private val username: String = System.getenv("STRATUS_TEST_USER") ?: "conformance"
    private val password: String = System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret"

    /** `http://name:8080/dav/` reduced to the `name:8080` somebody would type. */
    private val hostAndPort: String = url.substringAfter("://").substringBefore("/")

    private fun controller(scope: CoroutineScope): SignInController {
        val secure = InMemorySecureStore()
        return SignInController(
            prober = DavProber { credentials -> stratusHttpClient(CIO.create(), credentials) },
            instances = InstanceStore(secure),
            consent = ConsentStore(secure),
            scope = scope,
        )
    }

    // Two separate waits, because a StateFlow answers `first` with whatever it
    // currently holds: asking for "anything terminal" straight after answering a
    // question would be handed the question back.
    private suspend fun SignInController.awaitQuestion(): SignInState.Asking =
        state.first { it is SignInState.Asking } as SignInState.Asking

    private suspend fun SignInController.awaitOutcome(): SignInState =
        state.first { it is SignInState.Done || it is SignInState.Failed }

    @Test
    fun findsDavFromNothingButAHostAndAPort() = runTest {
        val signIn = controller(this)
        signIn.submit(SignInForm(hostAndPort, username, password))

        // No scheme was typed, so https is tried, does not answer, and the offer
        // of plain http has to be accepted before any password leaves.
        signIn.answer(signIn.awaitQuestion().question, accepted = true)

        val done = signIn.awaitOutcome()
        assertTrue(done is SignInState.Done, "was $done")
        assertEquals("http://$hostAndPort/dav/", done.baseUrl)
    }

    @Test
    fun tellsAWrongPasswordApartFromEverythingElse() = runTest {
        val signIn = controller(this)
        signIn.submit(SignInForm("http://$hostAndPort", username, "not the password"))

        signIn.answer(signIn.awaitQuestion().question, accepted = true)

        val failed = signIn.awaitOutcome() as SignInState.Failed
        assertTrue(failed.reason is SignInFailure.WrongCredentials, "was ${failed.reason}")
    }

    @Test
    fun tellsAWrongPathApartFromAMissingServer() = runTest {
        val signIn = controller(this)
        signIn.submit(SignInForm("http://$hostAndPort/definitely-not-here", username, password))

        signIn.answer(signIn.awaitQuestion().question, accepted = true)

        val failed = signIn.awaitOutcome() as SignInState.Failed
        val reason = failed.reason
        assertTrue(reason is SignInFailure.NotWebDav, "was $reason")
        assertEquals(listOf(Tried("/definitely-not-here/", 404)), reason.tried)
    }

    @Test
    fun neverAsksAboutPlaintextForAHostThatIsNotThere() = runTest {
        // The point of probing without credentials first: nobody is warned about
        // the risk of signing in to something that does not exist.
        val host = hostAndPort.substringBefore(':')
        val signIn = controller(this)
        signIn.submit(SignInForm("http://$host:1", username, password))

        val settled = signIn.awaitOutcome()
        assertTrue(settled is SignInState.Failed, "was $settled")
        assertTrue(settled.reason is SignInFailure.Unreachable, "was ${settled.reason}")
    }
}
