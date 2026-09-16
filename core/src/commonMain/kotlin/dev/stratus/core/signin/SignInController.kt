package dev.stratus.core.signin

import dev.stratus.core.net.Prober
import dev.stratus.core.store.ConsentStore
import dev.stratus.core.store.CredentialStore
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
 * dependency, and it lives in `:core` where the JVM target can test it. A
 * `ViewModel` in `:ui` would be neither.
 */
class SignInController(
    private val prober: Prober,
    private val credentials: CredentialStore,
    private val consent: ConsentStore,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = mutable.asStateFlow()

    private var running: Job? = null

    /** Picks up a session from a previous run, if there is one. */
    suspend fun restore(): Session? {
        val stored = credentials.load() ?: return null
        mutable.value = SignInState.Done(stored.first)
        return stored.first
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

    suspend fun signOut() {
        running?.cancel()
        credentials.clear()
        mutable.value = SignInState.Idle
    }

    private suspend fun advance(event: SignInEvent) {
        val step = SignInMachine.next(mutable.value, event)
        mutable.value = step.state
        for (effect in step.effects) perform(effect)
    }

    private suspend fun perform(effect: SignInEffect) {
        when (effect) {
            // The result comes straight back in as the next event, so the machine
            // decides what a probe meant and this only carries messages.
            is SignInEffect.Probe ->
                advance(SignInEvent.Attempted(prober.probe(effect.attempt, effect.credentials)))

            is SignInEffect.RememberConsent -> consent.remember(effect.host)
            is SignInEffect.Store -> credentials.save(effect.session, effect.credentials)
        }
    }
}
