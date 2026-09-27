package dev.stratus.app

import android.app.Application
import dev.stratus.core.appContainer
import dev.stratus.ui.CrashReports
import kotlinx.coroutines.runBlocking

/**
 * Exists for crash reports alone: the backup runs from WorkManager with no
 * activity at all, so starting them in [MainActivity] would miss exactly the
 * crashes nobody is there to see.
 */
class StratusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A Keystore decrypt of one small file, which is what blocking costs.
        if (runBlocking { appContainer(this@StratusApplication).reporting.granted() }) CrashReports.start()
    }
}
