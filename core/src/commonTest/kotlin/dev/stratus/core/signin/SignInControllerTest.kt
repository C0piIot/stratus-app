package dev.stratus.core.signin

import dev.stratus.core.net.Candidate
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.net.Prober
import dev.stratus.core.instance.InstanceStore
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
        InstanceStore(store),
        ConsentStore(store),
        TrustStore(store),
        scope,
        mintId = { "fixed-id" },
    ) to store

    @Test
    fun storesTheSessionOnceItIsProved() = runTest {
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (signIn, store) = controller(this, prober = prober)

        signIn.submit(form)
        testScheduler.advanceUntilIdle()

        assertTrue(signIn.state.value is SignInState.Done)
        assertTrue(store.values.keys.any { it.startsWith("instance/") }, "nothing was stored")
        // Stored, but never in a form that reads as a password at a glance.
        assertTrue(store.values.getValue("instance/fixed-id").contains("secret"))
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

        // Second time round, the same host is not asked about again.
        val again = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (second, _) = controller(this, store, again)
        second.submit(form.copy(address = "http://host"))
        testScheduler.advanceUntilIdle()
        assertTrue(second.state.value is SignInState.Done)
        assertEquals("secret", again.asked.single().second?.password)
    }

    @Test
    fun addsAnInstanceRatherThanReplacingTheOneThereIs() = runTest {
        val store = MemoryStore()
        var minted = 0
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }, { ProbeOutcome.IsWebDav(it) }))
        val instances = InstanceStore(store)
        val signIn = SignInController(
            prober, instances, ConsentStore(store), TrustStore(store), this, mintId = { "id-" + minted++ },
        )

        signIn.submit(form.copy(address = "https://one"))
        testScheduler.advanceUntilIdle()
        signIn.submit(form.copy(address = "https://two"))
        testScheduler.advanceUntilIdle()

        // Both kept, and the second is the one being looked at.
        assertEquals(listOf("id-0", "id-1"), instances.ids())
        assertEquals("id-1", instances.currentId())
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
        val instances = InstanceStore(store)
        instances.put(
            dev.stratus.core.instance.Instance("home", "https://old.example/dav/", "edu", backupEnabled = true, sources = setOf("camera")),
            Credentials("edu", "old-secret"),
        )
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.IsWebDav(it) }))
        val (signIn, _) = controller(this, store, prober)

        signIn.edit("home")
        assertEquals(SignInForm("https://old.example/dav/", "edu", "old-secret"), signIn.editing.value?.form)

        signIn.submit(SignInForm("https://new.example/dav/", "edu", "new-secret"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("home"), instances.ids(), "editing added a server instead")
        val edited = instances.instance("home")!!
        assertEquals("https://new.example/dav/", edited.baseUrl)
        assertTrue(edited.backupEnabled)
        assertEquals(setOf("camera"), edited.sources)
        assertEquals(Credentials("edu", "new-secret"), instances.credentials("home"))
        assertNull(signIn.editing.value)
    }

    @Test
    fun anEditTheServerRefusesChangesNothing() = runTest {
        val store = MemoryStore()
        val instances = InstanceStore(store)
        instances.put(dev.stratus.core.instance.Instance("home", "https://host/dav/", "edu"), Credentials("edu", "old-secret"))
        val prober = ScriptedProber(mutableListOf({ ProbeOutcome.Rejected(it) }))
        val (signIn, _) = controller(this, store, prober)

        signIn.edit("home")
        signIn.submit(SignInForm("https://host/dav/", "edu", "typo"))
        testScheduler.advanceUntilIdle()

        assertTrue(signIn.state.value is SignInState.Failed)
        assertEquals(Credentials("edu", "old-secret"), instances.credentials("home"))
    }

    @Test
    fun goingBackFromAnEditLeavesEverythingAsItWas() = runTest {
        val store = MemoryStore()
        val instances = InstanceStore(store)
        instances.put(dev.stratus.core.instance.Instance("home", "https://host/dav/", "edu"), Credentials("edu", "secret"))
        val (signIn, _) = controller(this, store, ScriptedProber(mutableListOf()))

        signIn.edit("home")
        assertTrue(signIn.canGoBack.value)
        signIn.back()

        assertTrue(signIn.state.value is SignInState.Done)
        assertNull(signIn.editing.value)
    }
}
