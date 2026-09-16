package dev.stratus.core.store

/**
 * Ships nowhere, in the same spirit as the JVM target itself: it exists so the
 * conformance suite can drive a real controller without a platform keystore.
 */
class InMemorySecureStore : SecureStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun read(key: String): String? = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}
