package dev.stratus.core.store

/**
 * Somewhere safe to put a string.
 *
 * Deliberately this small, and deliberately an interface rather than an
 * `expect class`. The Android implementation needs a `Context`, and every
 * `expect fun` factory ends up smuggling one in through a global set from
 * `Application.onCreate`; handing one in from the entry point is the same amount
 * of code without the global, and it lets everything above this be tested
 * against a fake.
 */
interface SecureStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun delete(key: String)
}
