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
        Phone.answerAll(allow = false)
        see("Back up to this server")
    }

    // stratus-app#81: without it, from Android 13, a backup runs with its
    // notification hidden. Asked once; a second toggle does not ask again.
    @Test
    fun turningBackupOnAsksOnceToShowItsNotification() {
        Phone.grantPhotos()
        signedIn()
        openServers()
        val toggle = { ui.onAllNodes(isToggleable())[0].performClick() }

        toggle()
        assertTrue(Phone.permissionDialogShown(), "nothing asked to show the backup's notification")
        Phone.allowAll()
        ui.waitUntil(5_000) { Phone.granted(android.Manifest.permission.POST_NOTIFICATIONS) }

        toggle()
        toggle()
        assertTrue(!Phone.permissionDialogShown(timeoutMs = 3_000), "it asked a second time")
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
