package dev.stratus.core.instance

import dev.stratus.core.net.Credentials
import dev.stratus.core.store.SecureStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

class InstanceStoreTest {

    private val secure = MemoryStore()
    private val store = InstanceStore(secure)

    private fun instance(id: String, url: String = "https://$id.example/dav/") =
        Instance(id, url, "edu")

    @Test
    fun holdsSeveralAndRemembersWhichIsBeingLookedAt() = runTest {
        store.put(instance("one"), Credentials("edu", "a"))
        store.put(instance("two"), Credentials("edu", "b"))

        assertEquals(listOf("one", "two"), store.ids())
        assertEquals("two", store.currentId())

        store.switchTo("one")
        assertEquals("one", store.current()?.id)
    }

    @Test
    fun keepsEachOnesPasswordApart() = runTest {
        store.put(instance("one"), Credentials("edu", "first"))
        store.put(instance("two"), Credentials("other", "second"))

        assertEquals("first", store.credentials("one")?.password)
        assertEquals("second", store.credentials("two")?.password)
        assertEquals("other", store.instance("two")?.username)
    }

    @Test
    fun savingTheSameInstanceAgainDoesNotDuplicateIt() = runTest {
        store.put(instance("one"), Credentials("edu", "a"))
        store.put(instance("one", url = "https://moved.example/dav/"), Credentials("edu", "a"))

        assertEquals(listOf("one"), store.ids())
        // The address is a property of the instance, so it can move without the
        // instance becoming a different one.
        assertEquals("https://moved.example/dav/", store.instance("one")?.baseUrl)
    }

    @Test
    fun changesSettingsWithoutBeingToldThePasswordAgain() = runTest {
        store.put(instance("one"), Credentials("edu", "secret"))
        store.update(store.instance("one")!!.copy(backupEnabled = true, backupRoot = "Camera"))

        assertEquals(true, store.instance("one")?.backupEnabled)
        assertEquals("Camera", store.instance("one")?.backupRoot)
        assertEquals("secret", store.credentials("one")?.password)
    }

    @Test
    fun forgettingOneLeavesTheOther() = runTest {
        store.put(instance("one"), Credentials("edu", "a"))
        store.put(instance("two"), Credentials("edu", "b"))

        store.remove("two")
        assertEquals(listOf("one"), store.ids())
        assertNull(store.instance("two"))
        assertNull(store.credentials("two"))
        // And the one being looked at moves to what is left rather than nowhere.
        assertEquals("one", store.current()?.id)
    }

    @Test
    fun forgettingTheLastOneLeavesNothingToLookAt() = runTest {
        store.put(instance("one"), Credentials("edu", "a"))
        store.remove("one")
        assertNull(store.current())
        assertEquals(emptyList(), store.ids())
    }

    @Test
    fun mintsIdentifiersThatAreNotTheAddress() = runTest {
        // Two instances of the same server are two instances; and one that moves
        // to a new domain is still the same one. Neither works if the id is the URL.
        assertNotEquals(newInstanceId(), newInstanceId())
        assertTrue(newInstanceId().length == 16, newInstanceId())
    }
}
