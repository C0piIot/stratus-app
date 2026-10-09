package dev.stratus.core.signin

import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.net.Prober
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives [SignInMachine] and performs what it asks for.
 *
 * A plain class rather than an androidx `ViewModel`: it needs no lifecycle
 * dependency, and it lives in `:core` where the JVM target can test it.
 *
 * Signing in is for when there is no server (stratus-app#131): getting to a
 * different one means signing out, which is also what drops the backup record.
 * Editing the one that is here keeps it.
 */
class SignInController(
    private val prober: Prober,
    private val instances: ServerStore,
    private val consent: ConsentStore,
    private val trust: TrustStore,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = mutable.asStateFlow()

    private var running: Job? = null

    /**
     * What the form is filled with when it is open on the server that exists
     * rather than on a new one (stratus-app#87).
     */
    private val editingFlow = MutableStateFlow<Editing?>(null)
    val editing: StateFlow<Editing?> = editingFlow.asStateFlow()

    private val backable = MutableStateFlow(false)

    /** Whether the form was opened from inside the app, so there is somewhere to go back to. */
    val canGoBack: StateFlow<Boolean> = backable.asStateFlow()

    /**
     * Opens the form on the server that exists, filled with what it has now.
     *
     * Everything is editable -- address, username, password -- and all of it is
     * proved again the way a first sign-in is, because a new address may be a
     * new certificate or a first time over plain http. **Editing keeps the
     * backup record**: a server moved to a new domain is the same server, and
     * what has been settled follows it there. Signing out is the other answer,
     * and it is the one that throws the record away.
     */
    suspend fun edit() {
        val instance = instances.instance() ?: return
        val credentials = instances.credentials() ?: return
        running?.cancel()
        editingFlow.value = Editing(SignInForm(instance.baseUrl, instance.username, credentials.password))
        backable.value = true
        mutable.value = SignInState.Idle
    }

    /** Leaves the form without changing anything. */
    suspend fun back() {
        running?.cancel()
        editingFlow.value = null
        backable.value = false
        restore()
    }

    /**
     * Reads the state back out of storage: the server, or none. Called at
     * startup and after signing in or out, so there is a single way for the
     * screen to learn what is true.
     */
    suspend fun restore(): Server? {
        val instance = instances.instance()
        mutable.value = instance?.let { SignInState.Done(it.baseUrl) } ?: SignInState.Idle
        return instance
    }

    fun submit(form: SignInForm) {
        running?.cancel()
        running = scope.launch {
            // One server (stratus-app#131). The interface never offers a blank
            // form while one is signed in -- the only way back to this screen
            // is `edit`, which fills it -- and this is the backstop under that,
            // because a sign-in that quietly replaced the server would take its
            // backup record with it and say nothing.
            if (editingFlow.value == null && instances.instance() != null) return@launch
            advance(SignInEvent.Submitted(form, consent.consentedHosts(), trust.pins()))
        }
    }

    fun answer(question: Question, accepted: Boolean) {
        running?.cancel()
        running = scope.launch { advance(SignInEvent.Answered(question, accepted)) }
    }

    fun cancel() {
        running?.cancel()
        mutable.value = SignInMachine.next(mutable.value, SignInEvent.Cancelled).state
    }

    private suspend fun advance(event: SignInEvent) {
        val step = SignInMachine.next(mutable.value, event)
        mutable.value = step.state
        for (effect in step.effects) perform(effect)
    }

    private suspend fun perform(effect: SignInEffect) {
        when (effect) {
            is SignInEffect.Probe ->
                advance(SignInEvent.Attempted(prober.probe(effect.attempt, effect.credentials, effect.pin)))

            is SignInEffect.RememberConsent -> consent.remember(effect.host)

            is SignInEffect.Pin -> trust.pin(effect.hostPort, effect.fingerprint)

            is SignInEffect.Store -> {
                // Edited, it keeps its backup root and its sources; new, it
                // gets both from scratch.
                val existing = editingFlow.value?.let { instances.instance() }
                val instance = existing?.copy(baseUrl = effect.baseUrl, username = effect.credentials.username)
                    ?: Server(baseUrl = effect.baseUrl, username = effect.credentials.username)
                instances.put(instance, effect.credentials)
                editingFlow.value = null
                backable.value = false
            }
        }
    }
}
