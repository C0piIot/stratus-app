package dev.stratus.core.store

/**
 * Whether somebody agreed to send crash reports (stratus-app#73).
 *
 * No until they say yes: a report carries a stack trace and the device it came
 * from to a service that is not their server, and an app whose whole point is
 * keeping photographs on hardware somebody owns does not get to decide that for
 * them.
 */
class ReportingConsent(private val secure: SecureStore) {

    suspend fun granted(): Boolean = secure.read(KEY) == YES

    suspend fun set(granted: Boolean) {
        if (granted) secure.write(KEY, YES) else secure.delete(KEY)
    }

    private companion object {
        const val KEY = "crash-reports"
        const val YES = "yes"
    }
}
