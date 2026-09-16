package dev.stratus.ui

/**
 * Where the platform's own back gesture is plugged in.
 *
 * Compose Multiplatform did not gain a common `BackHandler` until 1.12, and the
 * version pin cannot move until Google publishes an android-37 platform. Until
 * then Android wires its dispatcher to this and iOS, which has no system back
 * button at all, uses the one on the screen.
 */
class BackRequests {
    var onBack: (() -> Boolean)? = null
}
