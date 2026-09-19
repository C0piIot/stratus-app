package dev.stratus.core.store

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

class TrustStoreTest {

    private val store = MemoryStore()
    private val trust = TrustStore(store)
    private val fingerprint = "79:AC:73:12:E3:06:5B:23"

    @Test
    fun keepsAPinAgainstItsHostAndPort() = runTest {
        trust.pin("host:8443", fingerprint)
        assertEquals(fingerprint, trust.pins()["host:8443"])
        // A different port is a different server, and inheriting trust across
        // them is how a pin stops meaning anything.
        assertNull(trust.pins()["host:443"])
    }

    @Test
    fun survivesAnIpv6AddressWhichIsNothingButColons() = runTest {
        // The reason the stored form is separated by a space: splitting on a
        // colon would cut this key in the middle, and the fingerprint too.
        trust.pin("[2001:db8::1]:8443", fingerprint)
        assertEquals(fingerprint, trust.pins()["[2001:db8::1]:8443"])
    }

    @Test
    fun keepsSeveralAndForgetsOneAtATime() = runTest {
        trust.pin("one:443", fingerprint)
        trust.pin("two:443", "AB:CD")
        trust.forget("one:443")
        assertEquals(mapOf("two:443" to "AB:CD"), trust.pins())
    }

    @Test
    fun forgettingTheLastOneLeavesNothingBehind() = runTest {
        trust.pin("one:443", fingerprint)
        trust.forget("one:443")
        assertEquals(emptyMap(), trust.pins())
        assertNull(store.values["pinned-certificates"])
    }
}
