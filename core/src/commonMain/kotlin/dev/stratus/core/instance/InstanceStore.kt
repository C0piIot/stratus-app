package dev.stratus.core.instance

import dev.stratus.core.net.Credentials
import dev.stratus.core.store.SecureStore

/**
 * Every instance somebody has signed in to, and which one they are looking at.
 *
 * Several records over a key-value store rather than one blob holding a list:
 * [SecureStore] is the only part of this that cannot be tested in CI, and it
 * stays exactly as narrow as it was -- "put this string somewhere safe and give
 * it back" -- whether there is one instance or nine.
 */
class InstanceStore(private val secure: SecureStore) {

    suspend fun ids(): List<String> =
        secure.read(INDEX)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun all(): List<Instance> = ids().mapNotNull { load(it)?.first }

    suspend fun instance(id: String): Instance? = load(id)?.first

    suspend fun credentials(id: String): Credentials? = load(id)?.second

    /** Adds or replaces, and makes it the one being looked at. */
    suspend fun put(instance: Instance, credentials: Credentials) {
        secure.write(key(instance.id), InstanceBlob.encode(instance, credentials))
        if (instance.id !in ids()) {
            secure.write(INDEX, (ids() + instance.id).joinToString("\n"))
        }
        secure.write(CURRENT, instance.id)
    }

    /** Updates settings without touching the password, which it does not have. */
    suspend fun update(instance: Instance) {
        val existing = load(instance.id) ?: return
        secure.write(key(instance.id), InstanceBlob.encode(instance, existing.second))
    }

    /**
     * Forgets an instance.
     *
     * The caller is responsible for its cached rows: they are keyed by an id that
     * will never be handed out again, so leaving them behind means they are never
     * read and never freed either.
     */
    suspend fun remove(id: String) {
        secure.delete(key(id))
        secure.write(INDEX, (ids() - id).joinToString("\n"))
        if (currentId() == id) {
            val next = ids().firstOrNull { it != id }
            if (next == null) secure.delete(CURRENT) else secure.write(CURRENT, next)
        }
    }

    suspend fun currentId(): String? = secure.read(CURRENT)

    /** The one being looked at, or the first there is, or none. */
    suspend fun current(): Instance? {
        val chosen = currentId()?.let { instance(it) }
        return chosen ?: ids().firstNotNullOfOrNull { instance(it) }
    }

    suspend fun switchTo(id: String) {
        if (instance(id) != null) secure.write(CURRENT, id)
    }

    private suspend fun load(id: String): Pair<Instance, Credentials>? =
        secure.read(key(id))?.let(InstanceBlob::decode)

    private fun key(id: String) = "instance/$id"

    private companion object {
        // An id is hexadecimal, so the index may be delimited.
        const val INDEX = "instances"
        const val CURRENT = "current-instance"
    }
}
