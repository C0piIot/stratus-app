package dev.stratus.core.session

import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupState
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.store.ReportingConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One server and what its backup is doing. */
data class InstanceStatus(
    val instance: Instance,
    val state: BackupState,
    val failures: List<PendingUpload>,
)

data class SignedInState(
    val servers: List<Instance> = emptyList(),
    val currentId: String? = null,
    val overall: BackupState = BackupState.NeverRun,
    val statuses: List<InstanceStatus> = emptyList(),
    val access: MediaAccess = MediaAccess.None,
    val folders: List<MediaSource> = emptyList(),
    val reporting: Boolean = false,
)

/**
 * Everything the signed-in screens decide, where a test can reach it.
 *
 * It used to be state inside a composable, and that is how stratus-app#75
 * shipped: nothing re-read the photo access after somebody granted it, and
 * `:ui` has no tests to have noticed (stratus-app#77). What is left to the
 * interface is navigation and when to call [resumed] -- which is whenever the
 * app comes back to the front, because that is when a permission can have
 * changed without anything here being told.
 */
class SignedInController(
    private val instances: InstanceStore,
    private val backup: Backup,
    private val consent: ReportingConsent,
    /** Forgets an instance and what is cached about it; see AppContainer.forget. */
    private val forget: suspend (String) -> Unit,
    /** Asks the platform for the photo library, or null where nothing can. */
    private val askForAccess: (() -> Unit)?,
    /** Starts or stops the reporter itself, which lives outside `:core`. */
    private val reportingChanged: (Boolean) -> Unit,
) {
    private val mutable = MutableStateFlow(SignedInState())
    val state: StateFlow<SignedInState> = mutable.asStateFlow()

    private var accessRead = false

    /** Once, when the screens first appear. */
    suspend fun start() {
        mutable.update { it.copy(reporting = consent.granted()) }
        resumed()
    }

    /** The app is in front again: whatever changed while it was not, read it. */
    suspend fun resumed() {
        readAccess()
        refresh()
    }

    /** One pass of the status poll, which only runs while the app is in front. */
    suspend fun refresh() {
        val servers = instances.all()
        val statuses = servers.map {
            InstanceStatus(it, backup.status.of(it), backup.failures(it.id))
        }
        mutable.update {
            it.copy(
                servers = servers,
                currentId = instances.currentId(),
                overall = backup.status.across(servers),
                statuses = statuses,
            )
        }
    }

    /** The folder list is about to be shown, so the answer has to be current. */
    suspend fun openingFolders() = readAccess()

    suspend fun lookAt(id: String) {
        instances.switchTo(id)
        refresh()
    }

    suspend fun enableBackup(id: String, enabled: Boolean) {
        // The moment the library is needed is the moment to ask for it.
        if (enabled && mutable.value.access == MediaAccess.None) askForAccess?.invoke()
        backup.setEnabled(id, enabled)
        refresh()
    }

    suspend fun chooseSources(id: String, sources: Set<String>) {
        backup.setSources(id, sources)
        refresh()
    }

    suspend fun signOut(id: String) {
        forget(id)
        refresh()
    }

    suspend fun setReporting(granted: Boolean) {
        consent.set(granted)
        reportingChanged(granted)
        mutable.update { it.copy(reporting = granted) }
    }

    // The folders are only worth listing again when the access moved: a
    // MediaStore query per resume would be the cost of this with no answer.
    private suspend fun readAccess() {
        val access = backup.access()
        if (accessRead && access == mutable.value.access) return
        accessRead = true
        mutable.update { it.copy(access = access, folders = backup.sources()) }
    }
}
