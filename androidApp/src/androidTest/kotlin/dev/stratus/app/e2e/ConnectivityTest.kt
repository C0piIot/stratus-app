package dev.stratus.app.e2e

import org.junit.Test
import kotlin.test.assertTrue

/**
 * The server going away under the app, in the ways it does: switched off,
 * wedged so that it accepts and never answers, a transfer cut halfway, and the
 * phone losing the network altogether. The app has to say so, stay usable, and
 * pick up again once the server is back -- never crash, never hang, and never
 * leave half a file behind looking like a whole one.
 */
class ConnectivityTest : E2E() {

    private fun aFolderWithAFile() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/inside.txt")
    }

    @Test
    fun aServerSwitchedOffWhileBrowsingIsSaidAndBrowsingResumesWhenItIsBack() {
        aFolderWithAFile()
        signedIn()
        see(unique)

        link.down()
        tap(unique)
        see("Could not reach the server", timeoutMs = 30_000)

        link.up()
        tap("Try again")
        see("inside.txt")
    }

    @Test
    fun aServerThatStopsAnsweringTimesOutRatherThanHangingTheApp() {
        aFolderWithAFile()
        signedIn()
        see(unique)

        link.stall()
        tap(unique)
        // Twenty seconds is the request timeout; the rest is slack for a slow emulator.
        see("did not answer", timeoutMs = 45_000)

        link.up()
        tap("Try again")
        see("inside.txt")
    }

    @Test
    fun aDownloadCutHalfwayLeavesNoHalfFileInDownloads() {
        val bytes = ByteArray(400_000) { (it % 251).toByte() }
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/$unique.bin", bytes)
        signedIn()
        tap(unique)

        link.cutDownloadAfter(100_000)
        tap("$unique.bin")
        tap("Download")
        see("Could not fetch", timeoutMs = 30_000)

        val kept = Phone.downloads("$unique.bin")
        assertTrue(kept.none { it != bytes.size.toLong() }, "Downloads kept a truncated copy: $kept")
    }

    @Test
    fun losingTheNetworkIsSaidAndBrowsingResumesWhenItReturns() {
        aFolderWithAFile()
        launch()
        // Straight at the server, not through the link: this is about the
        // phone's own network, which a proxy on the loopback would not notice.
        signIn(address = "http://10.0.2.2:${Stratus.port}")
        tap("files")
        see(unique)

        Phone.airplane(true)
        tap(unique)
        see("Could not reach the server", timeoutMs = 30_000)

        Phone.airplane(false)
        tap("Try again")
        see("inside.txt", timeoutMs = 30_000)
    }
}
