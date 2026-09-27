package dev.stratus.core.session

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.Asset
import dev.stratus.core.backup.AssetPart
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.Connections
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.net.Credentials
import dev.stratus.core.store.ReportingConsent
import dev.stratus.core.store.SecureStore
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SessionMemoryStore : SecureStore {
    private val kept = mutableMapOf<String, String>()
    override suspend fun read(key: String) = kept[key]
    override suspend fun write(key: String, value: String) { kept[key] = value }
    override suspend fun delete(key: String) { kept.remove(key) }
}

/** A camera roll whose permission can be granted from outside, as Settings does. */
private class Roll : AssetSource {
    var access = MediaAccess.None
    var listings = 0
    override suspend fun access() = access
    override suspend fun sources(): List<MediaSource> {
        listings++
        return if (access == MediaAccess.None) emptyList() else listOf(MediaSource("camera", "Camera", 3))
    }
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long) = emptyList<Asset>()
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource = error("not read")
}

class SignedInControllerTest {

    private val store = SessionMemoryStore()
    private val instances = InstanceStore(store)
    private val roll = Roll()
    private val backup = Backup(
        instances,
        BackupDatabase(BundledSQLiteDriver().open(":memory:")),
        roll,
        Connections { null },
    )
    private var asked = 0
    private val reported = mutableListOf<Boolean>()
    private val forgotten = mutableListOf<String>()

    private val controller = SignedInController(
        instances = instances,
        backup = backup,
        consent = ReportingConsent(store),
        forget = { forgotten += it; instances.remove(it) },
        askForAccess = { asked++ },
        reportingChanged = { reported += it },
    )

    private suspend fun signedIn(id: String) =
        instances.put(Instance(id, "https://$id.example/dav/", "edu"), Credentials("edu", "secret"))

    // stratus-app#75: granted in Settings, and the Folders screen still said no.
    @Test
    fun aPermissionGrantedWhileAwayIsSeenOnResuming() = runTest {
        controller.start()
        assertEquals(MediaAccess.None, controller.state.value.access)
        assertTrue(controller.state.value.folders.isEmpty())

        roll.access = MediaAccess.Full
        controller.resumed()

        assertEquals(MediaAccess.Full, controller.state.value.access)
        assertEquals(listOf("camera"), controller.state.value.folders.map { it.id })
    }

    @Test
    fun openingFoldersReadsTheAccessAgain() = runTest {
        controller.start()
        roll.access = MediaAccess.Partial
        controller.openingFolders()
        assertEquals(MediaAccess.Partial, controller.state.value.access)
    }

    // A MediaStore query per resume would be the price of the fix with nothing
    // bought by it.
    @Test
    fun theFoldersAreListedAgainOnlyWhenTheAccessMoved() = runTest {
        roll.access = MediaAccess.Full
        controller.start()
        val before = roll.listings

        controller.resumed()
        controller.resumed()

        assertEquals(before, roll.listings)
    }

    @Test
    fun turningBackupOnWithoutAccessAsksForIt() = runTest {
        signedIn("home")
        controller.start()

        controller.enableBackup("home", false)
        assertEquals(0, asked, "turning it off needs no photographs")

        controller.enableBackup("home", true)
        assertEquals(1, asked)
        assertTrue(controller.state.value.servers.single().backupEnabled)
    }

    @Test
    fun turningBackupOnWithAccessAsksNothing() = runTest {
        signedIn("home")
        roll.access = MediaAccess.Full
        controller.start()

        controller.enableBackup("home", true)
        assertEquals(0, asked)
    }

    @Test
    fun reportingIsRememberedAndHandedToTheReporter() = runTest {
        controller.start()
        assertFalse(controller.state.value.reporting)

        controller.setReporting(true)

        assertEquals(listOf(true), reported)
        assertTrue(ReportingConsent(store).granted())
        assertTrue(controller.state.value.reporting)
    }

    @Test
    fun signingOutForgetsTheServerAndShowsItGone() = runTest {
        signedIn("home")
        signedIn("vps")
        controller.start()

        controller.signOut("home")

        assertEquals(listOf("home"), forgotten)
        assertEquals(listOf("vps"), controller.state.value.servers.map { it.id })
    }
}
