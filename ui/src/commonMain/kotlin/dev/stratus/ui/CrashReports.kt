package dev.stratus.ui

import io.sentry.kotlin.multiplatform.Sentry

/**
 * Crash reports to Sentry, for somebody who said yes (stratus-app#73).
 *
 * Started by the platform before anything else runs, so a crash in the first
 * screen or in a backup the system woke the app for is caught too, and started
 * or stopped again when the switch in the menu moves.
 */
object CrashReports {

    /** False in a build given no DSN, which then offers nothing to switch on. */
    val available: Boolean = REPORTING_DSN.isNotEmpty()

    fun start() {
        if (!available || Sentry.isEnabled()) return
        Sentry.init { options ->
            options.dsn = REPORTING_DSN
            // Nothing that shows what is on the screen, which is photographs.
            // Both are the default; written down so nobody turns them on.
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            // Compose's unhandled Kotlin exceptions otherwise arrive on iOS as
            // an anonymous C++ crash rather than the Kotlin stack; Sentry's own
            // advice for Compose Multiplatform.
            options.enableUnhandledCppExceptionMonitoring = false
        }
    }

    fun stop() {
        if (Sentry.isEnabled()) Sentry.close()
    }
}
