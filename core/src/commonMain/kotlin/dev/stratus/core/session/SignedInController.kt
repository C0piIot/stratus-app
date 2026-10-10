package dev.stratus.core.session

import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupState
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.store.ReportingConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the platform is asked to request, together, since it can only show one request at a time. */
enum class Ask { Photos, Notifications }

data class SignedInState(
    val server: Server? = null,
    val backupState: BackupState = BackupState.NeverRun,
    val failures: List<PendingUpload> = emptyList(),
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
    private val instances: ServerStore,
    private val backup: Backup,
    private val consent: ReportingConsent,
    /** Forgets the server and everything cached about it; see AppContainer.signOut. */
    private val signOut: suspend () -> Unit,
    /** Asks the platform for permissions, or null where nothing can. */
    private val ask: ((Set<Ask>) -> Unit)?,
    /** Whether the notification permission has been asked for once already. */
    private val notificationsAsked: AskedOnce,
    /** Starts or stops the reporter itself, which lives outside `:core`. */
    private val reportingChanged: (Boolean) -> Unit,
    /** Starts a pass now. The platform arranges one; this decides when to ask. */
    private val backUpNow: () -> Unit = {},
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
        val server = instances.instance()
        val state = if (server == null) BackupState.NeverRun else backup.status.of()
        val failures = if (server == null) emptyList() else backup.failures()
        mutable.update {
            it.copy(server = server, backupState = state, failures = failures)
        }
    }

    /** The folder list is about to be shown, so the answer has to be current. */
    suspend fun openingFolders() = readAccess()

    /** The Folders screen's button, which is about the library and nothing else. */
    fun askForPhotos() = ask?.invoke(setOf(Ask.Photos))

    /**
     * Which sources feed the backup, which is also what turns it on
     * (stratus-app#131): choosing none is choosing no backup.
     *
     * Asking for permissions hangs off this rather than off a separate toggle,
     * because this is now the moment the library is first needed -- and with
     * it the notification that says a backup is running, which from Android 13
     * is hidden without one (stratus-app#81). That one is asked once: a
     * refusal is an answer, not a first attempt.
     */
    suspend fun chooseSources(sources: Set<String>) {
        if (sources.isNotEmpty()) {
            val needed = buildSet {
                if (mutable.value.access == MediaAccess.None) add(Ask.Photos)
                if (!notificationsAsked.already()) add(Ask.Notifications)
            }
            if (needed.isNotEmpty() && ask != null) {
                ask.invoke(needed)
                if (Ask.Notifications in needed) notificationsAsked.remember()
            }
        }
        backup.setSources(sources)
        refresh()
    }

    /**
     * Whether a pass may spend somebody's data plan (stratus-app#133).
     *
     * Allowing it starts a pass rather than waiting for the next window: the
     * moment somebody turns this off is the moment they want what is queued to
     * go, and six hours later is not an answer.
     */
    suspend fun setOnlyOnWifi(only: Boolean) {
        backup.setOnlyOnWifi(only)
        if (!only) backUpNow()
        refresh()
    }

    suspend fun signOut() {
        signOut.invoke()
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
