package dev.stratus.core.store

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ReportingMemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

class ReportingConsentTest {

    private val store = ReportingMemoryStore()
    private val consent = ReportingConsent(store)

    @Test
    fun isNoUntilSomebodySaysYes() = runTest {
        assertFalse(consent.granted())
    }

    @Test
    fun isRememberedAndCanBeWithdrawn() = runTest {
        consent.set(true)
        assertTrue(ReportingConsent(store).granted())

        consent.set(false)
        assertFalse(consent.granted())
        // Withdrawn means gone, not a "no" kept beside the other secrets.
        assertTrue(store.values.isEmpty())
    }
}
