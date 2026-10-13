package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.net.Credentials
import dev.stratus.core.server.Server
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class KeptConnectionsTest {

    private val server = Server("http://host/", "edu")
    private var credentials: Credentials? = Credentials("edu", "secret")
    private var pin: String? = null
    private var built = 0

    private fun connections() = KeptConnections(
        credentials = { credentials },
        pinOf = { pin },
        build = { instance, _, _ ->
            built++
            val client = HttpClient(MockEngine { respondOk() })
            Connection(client, DavClient(client, instance.baseUrl))
        },
    )

    @Test
    fun theSameServerIsTheSameClient() = runTest {
        // The whole point: a client is a connection pool, so a second one
        // cannot reuse what the first opened.
        val connections = connections()
        assertSame(connections.to(server), connections.to(server))
        assertEquals(1, built)
    }

    @Test
    fun aNewPasswordIsANewClientAndTheOldOneIsClosed() = runTest {
        // It is holding the old password in a header, so keeping it would be
        // authenticating as somebody who has been edited away (stratus-app#131).
        val connections = connections()
        val first = requireNotNull(connections.to(server))

        credentials = Credentials("edu", "a new secret")
        val second = requireNotNull(connections.to(server))

        assertEquals(2, built)
        assertFalse(first.http.isActive, "the client with the old password was left running")
        assertSame(second, connections.to(server))
    }

    @Test
    fun soIsAnAddressAndSoIsAPin() = runTest {
        val connections = connections()
        connections.to(server)
        connections.to(Server("http://elsewhere/", "edu"))
        assertEquals(2, built)

        // The one a cache keyed by the server alone would miss: the pin is in
        // the engine, so a certificate vouched for mid-session changes what a
        // client is and not merely what it is allowed to reach.
        pin = "aa:bb"
        connections.to(Server("http://elsewhere/", "edu"))
        assertEquals(3, built)
    }

    @Test
    fun signingOutTakesTheClientWithIt() = runTest {
        val connections = connections()
        val first = requireNotNull(connections.to(server))

        connections.clear()
        assertFalse(first.http.isActive, "the client holding the password outlived the sign-out")

        credentials = null
        assertNull(connections.to(server), "signed out is an answer, not a client")
        assertEquals(1, built)
    }

    @Test
    fun aBurstOfCallersGetsOneClient() = runTest {
        // A picker binds forty rows at once, and each of them asks.
        val connections = connections()
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(coroutineContext)
            val all = (1..12).map { scope.async { delay(1); connections.to(server) } }.awaitAll()
            assertEquals(1, built)
            all.forEach { assertSame(all.first(), it) }
        }
    }
}
