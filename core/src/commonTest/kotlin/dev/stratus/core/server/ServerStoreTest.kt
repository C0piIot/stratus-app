package dev.stratus.core.server

import dev.stratus.core.net.Credentials
import dev.stratus.core.store.SecureStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class MemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

class ServerStoreTest {

    private val secure = MemoryStore()
    private val store = ServerStore(secure)

    private fun instance(url: String = "https://one.example/dav/") = Server(url, "edu")

    @Test
    fun holdsTheServerAndItsPassword() = runTest {
        store.put(instance(), Credentials("edu", "secret"))

        assertEquals("https://one.example/dav/", store.instance()?.baseUrl)
        assertEquals("secret", store.credentials()?.password)
    }

    @Test
    fun holdsOneAndNotTwo() = runTest {
        // There is no way to add a second (stratus-app#131). Putting is how a
        // sign-in lands, and what the controller refuses is doing it twice --
        // here, the second simply replaces the first rather than joining it.
        store.put(instance(), Credentials("edu", "a"))
        store.put(instance("https://two.example/dav/"), Credentials("other", "b"))

        assertEquals("https://two.example/dav/", store.instance()?.baseUrl)
        assertEquals("other", store.instance()?.username)
        assertEquals("b", store.credentials()?.password)
    }

    @Test
    fun theAddressMovesWithoutTheServerBecomingADifferentOne() = runTest {
        store.put(instance(), Credentials("edu", "a"))
        store.update(store.instance()!!.copy(baseUrl = "https://moved.example/dav/"))

        assertEquals("https://moved.example/dav/", store.instance()?.baseUrl)
        assertEquals("a", store.credentials()?.password)
    }

    @Test
    fun changesSettingsWithoutBeingToldThePasswordAgain() = runTest {
        store.put(instance(), Credentials("edu", "secret"))
        store.update(store.instance()!!.copy(backupRoot = "Camera", sources = setOf("dcim")))

        assertEquals("Camera", store.instance()?.backupRoot)
        // Sources are the switch, so choosing one is what turns backup on.
        assertEquals(true, store.instance()?.backupEnabled)
        assertEquals("secret", store.credentials()?.password)
    }

    @Test
    fun signingOutLeavesNothing() = runTest {
        store.put(instance(), Credentials("edu", "a"))

        store.clear()

        assertNull(store.instance())
        assertNull(store.credentials())
    }
}
