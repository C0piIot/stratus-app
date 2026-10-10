package dev.stratus.core.backup

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * logcat as it happens, and one Sentry event per pass carrying the rest.
 *
 * The event is what makes this readable without a cable, which is the point:
 * a backup is watched over days and the phone is not plugged into anything for
 * most of them. It is the shape `AndroidCaster` already reports a cast in --
 * one event, the whole trail in an extra -- rather than a breadcrumb per file,
 * which would be capped by a ring buffer and only ever seen attached to a
 * crash.
 *
 * **It arrives only where crash reports are switched on**, since that is what
 * initialises Sentry at all (stratus-app#73); with them off this is a no-op
 * and logcat is the whole of it.
 */
internal actual fun platformLogSink(): LogSink = object : LogSink {
    /** A tag of its own, so `adb logcat -s StratusBackup` is the whole filter. */
    override fun line(text: String) {
        Log.i("StratusBackup", text)
    }

    override fun pass(summary: String, trail: List<String>) {
        Sentry.captureMessage(summary, SentryLevel.INFO) { scope ->
            // One issue for all of them rather than one per way of stopping:
            // what is being watched is pass after pass after pass, and that
            // reads as a list or it does not read at all.
            scope.fingerprint = listOf("backup-pass")
            scope.setExtra("pass", trail.joinToString("\n"))
        }
    }
}
