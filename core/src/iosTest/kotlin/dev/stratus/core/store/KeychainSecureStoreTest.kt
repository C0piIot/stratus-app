package dev.stratus.core.store

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Runs on the simulator, on the macOS runner CI already pays nothing for. The
 * simulator's Keychain handles generic-password items, so this is the real thing
 * rather than a stand-in.
 */
class KeychainSecureStoreTest {

    // A service of its own per run, so a leftover item cannot fail a later one.
    private val store = KeychainSecureStore("dev.stratus.test.${Random.nextInt()}")

    @Test
    fun keepsAndReturnsAValue() = runTest {
        store.write("session", "hello")
        assertEquals("hello", store.read("session"))
    }

    @Test
    fun replacesAValueThatIsAlreadyThere() = runTest {
        // SecItemAdd refuses rather than replaces, so this is the case an
        // implementation gets wrong first.
        store.write("session", "first")
        store.write("session", "second")
        assertEquals("second", store.read("session"))
    }

    @Test
    fun forgetsWhatWasDeleted() = runTest {
        store.write("session", "hello")
        store.delete("session")
        assertNull(store.read("session"))
    }

    @Test
    fun survivesAPasswordThatIsNotAscii() = runTest {
        store.write("session", "contraseña con emoji 📷")
        assertEquals("contraseña con emoji 📷", store.read("session"))
    }

    @Test
    fun answersNothingForAKeyItNeverSaw() = runTest {
        assertNull(store.read("never-written"))
    }
}
