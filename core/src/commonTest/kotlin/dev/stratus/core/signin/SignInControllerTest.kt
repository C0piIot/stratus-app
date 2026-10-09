package dev.stratus.core.signin

import dev.stratus.core.net.Candidate
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.net.Prober
import dev.stratus.core.server.ServerStore
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.TrustStore
import dev.stratus.core.store.SecureStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore : SecureStore {
    val values = mutableMapOf<String, String>()
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { values[key] = value }
    override suspend fun delete(key: String) { values.remove(key) }
}

private class ScriptedProber(private val answers: MutableList<(Candidate) -> ProbeOutcome>) : Prober {
    val asked = mutableListOf<Pair<Candidate, Credentials?>>()
    val pins = mutableListOf<String?>()
    override suspend fun probe(attempt: Candidate, credentials: Credentials?, pin: String?): ProbeOutcome {
        asked += attempt to credentials
        pins += pin
        return answers.removeFirst()(attempt)
    }
}

class SignInControllerTest {

    private val form = SignInForm("host", "edu", "secret")

    private fun controller(
        scope: TestScope,
        store: MemoryStore = MemoryStore(),
        prober: ScriptedProber,
    ) = SignInController(
        prober,
        ServerStore(store),
        ConsentStore(store),
        TrustStore(store),
        scope,
    ) to store

    @Test
    fun storesTheSessionOnceItIsProved() = runTest {
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (signIn, store) = controller(this, prober = prober)

        signIn.submit(form)
        testScheduler.advanceUntilIdle()

        assertTrue(signIn.state.value is SignInState.Done)
        assertTrue(store.values.containsKey("server"), "nothing was stored")
        // Stored, but never in a form that reads as a password at a glance.
        assertTrue(store.values.getValue("server").contains("secret"))
    }

    @Test
    fun walksTheQueueUntilSomethingAnswers() = runTest {
        val prober = ScriptedProber(
            mutableListOf(
                { ProbeOutcome.NotWebDav(it, 404) },
                { ProbeOutcome.IsWebDav(it) },
            ),
        )
        val (signIn, _) = controller(this, prober = prober)

        signIn.submit(form)
        testScheduler.advanceUntilIdle()

        // The root first, and the one that answered is the one that is kept.
        assertEquals(listOf("/", "/dav/"), prober.asked.map { it.first.path })
        assertEquals("https://host/dav/", (signIn.state.value as SignInState.Done).baseUrl)
    }

    @Test
    fun asksOnceAndThenRemembers() = runTest {
        val store = MemoryStore()
        val first = ScriptedProber(
            mutableListOf({ ProbeOutcome.Reachable(it) }, { ProbeOutcome.IsWebDav(it) }),
        )
        val (signIn, _) = controller(this, store, first)

        signIn.submit(form.copy(address = "http://host"))
        testScheduler.advanceUntilIdle()
        val asking = signIn.state.value as SignInState.Asking
        assertNull(first.asked.single().second, "a password went out before consent")

        signIn.answer(asking.question, accepted = true)
        testScheduler.advanceUntilIdle()
        assertTrue(signIn.state.value is SignInState.Done)

        // Second time round, the same host is not asked about again. Signed
        // out in between, because signing in is refused while a server is here
        // -- and the consent is the host's, so it outlives the server.
        ServerStore(store).clear()
        val again = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (second, _) = controller(this, store, again)
        second.submit(form.copy(address = "http://host"))
        testScheduler.advanceUntilIdle()
        assertTrue(second.state.value is SignInState.Done)
        assertEquals("secret", again.asked.single().second?.password)
    }

    @Test
    fun signingInIsRefusedWhileAServerIsAlreadyHere() = runTest {
        // There is one (stratus-app#131). Getting to a different server means
        // signing out, which is also what drops the backup record -- and a
        // sign-in that quietly replaced it would take that record with it and
        // say nothing.
        val store = MemoryStore()
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }, { ProbeOutcome.IsWebDav(it) }))
        val instances = ServerStore(store)
        val signIn = SignInController(prober, instances, ConsentStore(store), TrustStore(store), this)

        signIn.submit(form.copy(address = "https://one"))
        testScheduler.advanceUntilIdle()
        signIn.submit(form.copy(address = "https://two"))
        testScheduler.advanceUntilIdle()

        assertEquals("https://one/", instances.instance()?.baseUrl, "the second sign-in replaced the first")
    }

    @Test
    fun picksUpWhereItLeftOff() = runTest {
        val store = MemoryStore()
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (first, _) = controller(this, store, prober)
        first.submit(form)
        testScheduler.advanceUntilIdle()

        val (second, _) = controller(this, store, ScriptedProber(mutableListOf()))
        assertEquals("https://host/", second.restore()?.baseUrl)
        assertTrue(second.state.value is SignInState.Done)
    }

    // stratus-app#87: a server's password changed, or it moved, and the only
    // way to follow it was to remove it -- and its backup record with it.
    @Test
    fun editingKeepsTheInstanceAndItsBackupSettings() = runTest {
        val store = MemoryStore()
        val instances = ServerStore(store)
        instances.put(
            dev.stratus.core.server.Server("https://old.example/dav/", "edu", sources = setOf("camera")),
            Credentials("edu", "old-secret"),
        )
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (signIn, _) = controller(this, store, prober)

        signIn.edit()
        assertEquals(SignInForm("https://old.example/dav/", "edu", "old-secret"), signIn.editing.value?.form)

        signIn.submit(SignInForm("https://new.example/dav/", "edu", "new-secret"))
        testScheduler.advanceUntilIdle()

        val edited = instances.instance()!!
        assertEquals("https://new.example/dav/", edited.baseUrl)
        // The sources survive the move, which is what the record hangs off.
        assertTrue(edited.backupEnabled)
        assertEquals(setOf("camera"), edited.sources)
        assertEquals(Credentials("edu", "new-secret"), instances.credentials())
        assertNull(signIn.editing.value)
    }

    @Test
    fun anEditTheServerRefusesChangesNothing() = runTest {
        val store = MemoryStore()
        val instances = ServerStore(store)
        instances.put(dev.stratus.core.server.Server("https://host/dav/", "edu"), Credentials("edu", "old-secret"))
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.Rejected(it) }))
        val (signIn, _) = controller(this, store, prober)

        signIn.edit()
        signIn.submit(SignInForm("https://host/dav/", "edu", "typo"))
        testScheduler.advanceUntilIdle()

        assertTrue(signIn.state.value is SignInState.Failed)
        assertEquals(Credentials("edu", "old-secret"), instances.credentials())
    }

    @Test
    fun goingBackFromAnEditLeavesEverythingAsItWas() = runTest {
        val store = MemoryStore()
        val instances = ServerStore(store)
        instances.put(dev.stratus.core.server.Server("https://host/dav/", "edu"), Credentials("edu", "secret"))
        val (signIn, _) = controller(this, store, ScriptedProber(mutableListOf()))

        signIn.edit()
        assertTrue(signIn.canGoBack.value)
        signIn.back()

        assertTrue(signIn.state.value is SignInState.Done)
        assertNull(signIn.editing.value)
    }
}
