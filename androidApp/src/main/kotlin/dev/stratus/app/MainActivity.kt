package dev.stratus.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.stratus.core.appContainer
import dev.stratus.core.backup.AndroidAssetSource
import dev.stratus.core.backup.BackupWorker
import dev.stratus.core.session.Ask
import dev.stratus.ui.App
import dev.stratus.ui.BackRequests

class MainActivity : ComponentActivity() {

    private val photos by lazy { AndroidAssetSource(applicationContext).permissions() }

    // One launcher for every permission, because Android shows one request at a
    // time and a second launched beside the first is dropped.
    //
    // After a second refusal Android stops showing the dialog and answers no at
    // once, so a request for the library that changed nothing opens the settings
    // page instead: it is the only place left where somebody can say yes. Only
    // for the library -- a refused notification is an answer, not a dead end.
    private val askFor = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val library = granted.filterKeys { it in photos }
        if (library.isNotEmpty() && library.values.none { it } &&
            photos.none { shouldShowRequestPermissionRationale(it) }
        ) {
            // Caught rather than resolved first, since package visibility can
            // hide the settings app from the question: a build of Android with
            // no settings app is real -- the emulator image the tests run on is
            // one -- and starting what nothing handles is a crash.
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    private fun permissionsFor(asks: Set<Ask>): Array<String> = buildList {
        if (Ask.Photos in asks) addAll(photos)
        // A runtime permission from Android 13 only; below it, notifications
        // are simply allowed.
        if (Ask.Notifications in asks && Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = appContainer(applicationContext)
        val back = BackRequests()

        // Compose Multiplatform has no common BackHandler at the version this
        // project is pinned to, so the system gesture is wired here. Walking up
        // the tree is what back means while browsing; anywhere else it leaves.
        onBackPressedDispatcher.addCallback(this) {
            if (back.onBack?.invoke() != true) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        // Asks Android to come back every few hours. Idempotent, so doing it on
        // every launch is how it survives a reinstall or a cleared app.
        BackupWorker.schedule(applicationContext)

        setContent {
            App(
                container,
                back,
                onBackUpNow = { BackupWorker.now(applicationContext) },
                onAsk = { asks -> askFor.launch(permissionsFor(asks)) },
            )
        }
    }
}
