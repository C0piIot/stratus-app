package dev.stratus.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.stratus.core.appContainer
import dev.stratus.core.backup.AndroidAssetSource
import dev.stratus.core.backup.BackupWorker
import dev.stratus.ui.App
import dev.stratus.ui.BackRequests

class MainActivity : ComponentActivity() {

    private val photos by lazy { AndroidAssetSource(applicationContext).permissions() }

    // After a second refusal Android stops showing the dialog and answers no at
    // once, so a request that changed nothing opens the settings page instead:
    // it is the only place left where somebody can say yes.
    private val askForPhotos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.none { it } && photos.none { shouldShowRequestPermissionRationale(it) }) {
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
                onRequestAccess = { askForPhotos.launch(photos) },
            )
        }
    }
}
