package dev.stratus.core.signin

import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.instance.newInstanceId
import dev.stratus.core.net.Prober
import dev.stratus.core.store.ConsentStore
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
    private val scope: CoroutineScope,
    // Injected so a test can assert on a whole stored record. Minting it here
    // rather than in the machine is what keeps the machine a pure function.
    private val mintId: () -> String = ::newInstanceId,
) {
    private val mutable = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = mutable.asStateFlow()

    private var running: Job? = null

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
            advance(SignInEvent.Submitted(form, consent.consentedHosts()))
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
                advance(SignInEvent.Attempted(prober.probe(effect.attempt, effect.credentials)))

            is SignInEffect.RememberConsent -> consent.remember(effect.host)

            is SignInEffect.Store -> instances.put(
                Instance(
                    id = mintId(),
                    baseUrl = effect.baseUrl,
                    username = effect.credentials.username,
                ),
                effect.credentials,
            )
        }
    }
}
