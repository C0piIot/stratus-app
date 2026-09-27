package dev.stratus.core.signin

import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.instance.newInstanceId
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
 * Signing in **adds** an instance rather than replacing the one there, which is
 * the whole difference between one server and several.
 */
class SignInController(
    private val prober: Prober,
    private val instances: InstanceStore,
    private val consent: ConsentStore,
    private val trust: TrustStore,
    private val scope: CoroutineScope,
    // Injected so a test can assert on a whole stored record. Minting it here
    // rather than in the machine is what keeps the machine a pure function.
    private val mintId: () -> String = ::newInstanceId,
) {
    private val mutable = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = mutable.asStateFlow()

    private var running: Job? = null

    /**
     * The instance being edited, if the form is open on one rather than on a
     * new server -- and what to fill it with (stratus-app#87).
     */
    private val editingFlow = MutableStateFlow<Editing?>(null)
    val editing: StateFlow<Editing?> = editingFlow.asStateFlow()

    private val backable = MutableStateFlow(false)

    /** Whether the form was opened from inside the app, so there is somewhere to go back to. */
    val canGoBack: StateFlow<Boolean> = backable.asStateFlow()

    /**
     * Opens the form on an instance that exists, filled with what it has now.
     *
     * Everything is editable -- address, username, password -- and all of it is
     * proved again the way a first sign-in is, because a new address may be a
     * new certificate or a first time over plain http. What does not change is
     * the id: a server moved to a new domain is the same server, and its backup
     * history follows it there.
     */
    suspend fun edit(id: String) {
        val instance = instances.instance(id) ?: return
        val credentials = instances.credentials(id) ?: return
        running?.cancel()
        editingFlow.value = Editing(id, SignInForm(instance.baseUrl, instance.username, credentials.password))
        backable.value = true
        mutable.value = SignInState.Idle
    }

    /** Opens an empty form for one more server, with a way back. */
    fun addAnother() {
        cancel()
        editingFlow.value = null
        backable.value = true
    }

    /** Leaves the form without changing anything. */
    suspend fun back() {
        running?.cancel()
        editingFlow.value = null
        backable.value = false
        restore()
    }

    /**
     * Reads the state back out of storage: which instance is being looked at, or
     * none. Called at startup and after anything that adds or forgets one, so
     * there is a single way for the screen to learn what is true.
     */
    suspend fun restore(): Instance? {
        val instance = instances.current()
        mutable.value = instance?.let { SignInState.Done(it.baseUrl) } ?: SignInState.Idle
        return instance
    }

    fun submit(form: SignInForm) {
        running?.cancel()
        running = scope.launch {
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
                // Edited, it keeps its id and its backup settings; new, it
                // gets both from scratch.
                val existing = editingFlow.value?.let { instances.instance(it.id) }
                val instance = existing?.copy(baseUrl = effect.baseUrl, username = effect.credentials.username)
                    ?: Instance(id = mintId(), baseUrl = effect.baseUrl, username = effect.credentials.username)
                instances.put(instance, effect.credentials)
                editingFlow.value = null
                backable.value = false
            }
        }
    }
}
