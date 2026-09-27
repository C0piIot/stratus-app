package dev.stratus.core.session

import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupState
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.instance.Instance
import dev.stratus.core.instance.InstanceStore
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.ProbeOutcome
import dev.stratus.core.store.ReportingConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the platform is asked to request, together, since it can only show one request at a time. */
enum class Ask { Photos, Notifications }

/** One server and what its backup is doing. */
data class InstanceStatus(
    val instance: Instance,
    val state: BackupState,
    val failures: List<PendingUpload>,
)

/** What came of offering a server a new password. */
sealed interface PasswordChange {
    data object Saved : PasswordChange

    /** The server said no, so nothing was kept: the old one is still there. */
    data object Rejected : PasswordChange

    /** No answer either way, so nothing was kept -- a password is not saved unproved. */
    data object Unreachable : PasswordChange
}

data class SignedInState(
    val servers: List<Instance> = emptyList(),
    val currentId: String? = null,
    val overall: BackupState = BackupState.NeverRun,
    val statuses: List<InstanceStatus> = emptyList(),
    val access: MediaAccess = MediaAccess.None,
    val folders: List<MediaSource> = emptyList(),
    val reporting: Boolean = false,
    /**
     * Moves when a server's password does, so whatever holds a connection
     * built with the old one -- the browser -- is built again.
     */
    val credentialsRevision: Int = 0,
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
    /** Asks the platform for permissions, or null where nothing can. */
    private val ask: ((Set<Ask>) -> Unit)?,
    /** Whether the notification permission has been asked for once already. */
    private val notificationsAsked: AskedOnce,
    /** Starts or stops the reporter itself, which lives outside `:core`. */
    private val reportingChanged: (Boolean) -> Unit,
    /** One authenticated request to an instance with these credentials, as sign-in makes. */
    private val prove: suspend (Instance, Credentials) -> ProbeOutcome = { _, _ -> error("not wired") },
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
        if (enabled) {
            // The moment the library is needed is the moment to ask for it --
            // and for the notification that says a backup is running, which
            // from Android 13 is hidden without one (stratus-app#81). That one
            // is asked once: a refusal is an answer, not a first attempt.
            val needed = buildSet {
                if (mutable.value.access == MediaAccess.None) add(Ask.Photos)
                if (!notificationsAsked.already()) add(Ask.Notifications)
            }
            if (needed.isNotEmpty() && ask != null) {
                ask.invoke(needed)
                if (Ask.Notifications in needed) notificationsAsked.remember()
            }
        }
        backup.setEnabled(id, enabled)
        refresh()
    }

    /** The Folders screen's button, which is about the library and nothing else. */
    fun askForPhotos() = ask?.invoke(setOf(Ask.Photos))

    suspend fun chooseSources(id: String, sources: Set<String>) {
        backup.setSources(id, sources)
        refresh()
    }

    /**
     * Tries [password] against the server before keeping it, the way sign-in
     * does: a typo should fail at the keyboard, not at three in the morning in
     * a backup nobody watches.
     */
    suspend fun changePassword(id: String, password: String): PasswordChange {
        val instance = instances.instance(id) ?: return PasswordChange.Rejected
        val credentials = Credentials(instance.username, password)
        return when (prove(instance, credentials)) {
            is ProbeOutcome.IsWebDav -> {
                instances.updateCredentials(id, credentials)
                mutable.update { it.copy(credentialsRevision = it.credentialsRevision + 1) }
                PasswordChange.Saved
            }
            is ProbeOutcome.Rejected -> PasswordChange.Rejected
            else -> PasswordChange.Unreachable
        }
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

/** A question the app puts once and then leaves alone, whatever the answer was. */
class AskedOnce(private val secure: dev.stratus.core.store.SecureStore, private val key: String) {
    suspend fun already(): Boolean = secure.read(key) != null
    suspend fun remember() = secure.write(key, "asked")
}
