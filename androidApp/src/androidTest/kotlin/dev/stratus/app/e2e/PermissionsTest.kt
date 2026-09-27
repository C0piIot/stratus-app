package dev.stratus.app.e2e

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import org.junit.Test
import kotlin.test.assertTrue

/** stratus-app#75, and everything around asking for the photo library. */
class PermissionsTest : E2E() {

    private fun seedAFolder() = Phone.photograph("$unique.jpg", unique, ByteArray(1_000) { 1 })

    private fun openFolders() {
        openServers()
        tap("All folders")
        see("Folders")
    }

    @Test
    fun theFoldersScreenAsksAndShowsTheFoldersOnceAllowed() {
        seedAFolder()
        signedIn()
        openFolders()
        see("cannot read your photographs")

        tap("Allow access to photos")
        assertTrue(Phone.permissionDialogShown(), "the system never asked")
        Phone.allowAll()

        see(unique)
    }

    @Test
    fun turningBackupOnWithoutAccessAsksForIt() {
        signedIn()
        openServers()
        ui.onAllNodes(isToggleable())[0].performClick()
        assertTrue(Phone.permissionDialogShown(), "turning backup on asked for nothing")
        Phone.deny()
        see("Back up to this server")
    }

    // Twice refused, Android answers no without showing anything, and the app
    // sends somebody to the one place left where they can say yes.
    @Test
    fun refusedTwiceItOpensTheSettingsInstead() {
        signedIn()
        openFolders()

        tap("Allow access to photos")
        assertTrue(Phone.permissionDialogShown())
        Phone.deny()
        tap("Allow access to photos")
        assertTrue(Phone.permissionDialogShown())
        Phone.deny()

        tap("Allow access to photos")
        Phone.device.waitForIdle()
        val front = Phone.inFront()
        assertTrue(
            front == "com.android.settings" || front == Phone.context.packageName,
            "after two refusals $front was in front",
        )
        if (front != Phone.context.packageName) Phone.backToApp()
        see("Folders")
    }

    // The bug itself: granted in Settings, and the screen still said no.
    @Test
    fun aPermissionGrantedFromOutsideIsSeenOnComingBack() {
        seedAFolder()
        signedIn()
        openFolders()
        see("cannot read your photographs")

        Phone.device.pressHome()
        Phone.grantPhotos()
        Phone.toHomeAndBack()

        see(unique)
    }
}
