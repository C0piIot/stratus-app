package dev.stratus.core.backup

import dev.stratus.core.hostAppContainer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * When a pass happens on iOS, and what little of that is ours to decide
 * (stratus-app#20).
 *
 * The background session finishes what it was given on its own -- that is the
 * whole point of it, and it keeps going with the app dead. **What still needs
 * the app awake is looking at the camera roll and queueing what is new**, so
 * that is what this asks the system for.
 *
 * The Android twin asks WorkManager for six hours and gets roughly that. Here
 * there is no such promise: `BGTaskScheduler` grants time when it chooses --
 * in practice while charging and on wifi, often overnight -- and a request is
 * a hint rather than a schedule. The README says so and nothing here should
 * suggest otherwise.
 *
 * The pass itself is `Backup.pass`, shared and tested. What is here is the
 * waking and the staying alive, which are not decisions about backing up.
 */
@OptIn(ExperimentalForeignApi::class)
object BackupTasks {

    /**
     * Declared in the Info.plist too, under `BGTaskSchedulerPermittedIdentifiers`.
     * The system refuses to register a handler for one that is not.
     */
    const val IDENTIFIER = "dev.stratus.backup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registers the handler, which has to happen before the app finishes
     * launching -- the system calls it immediately for a task it was already
     * holding, and refuses one registered late.
     */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = IDENTIFIER,
            usingQueue = null,
        ) { task -> run(task as? BGProcessingTask ?: return@registerForTaskWithIdentifier) }
    }

    /**
     * Asks to be woken.
     *
     * Asked again after every pass, because a request is spent once it has
     * been granted: one that is never renewed is a backup that worked for a
     * day. Network required, and no charger required -- the system prefers
     * one anyway, and insisting would mean somebody who charges overnight on
     * a battery bank never backs up.
     */
    fun schedule() {
        val request = BGProcessingTaskRequest(IDENTIFIER).apply {
            requiresNetworkConnectivity = true
            requiresExternalPower = false
            // Not before an hour, so a pass that finds nothing does not ask to
            // be woken straight back up.
            earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(AN_HOUR)
        }
        // Throws where there is no entitlement or the identifier is undeclared,
        // and a backup that crashed the app over its own scheduling would be
        // worse than one that waits for somebody to open it.
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
    }

    private fun run(task: BGProcessingTask) {
        // Asked for again first: if the pass below is cut short, the request
        // is already in.
        schedule()

        var stopped = false
        task.expirationHandler = { stopped = true }

        scope.launch {
            val outcome = runCatching {
                hostAppContainer().backup.pass(keepGoing = { !stopped })
            }.getOrNull()
            // Told truthfully: the system schedules an app that says it
            // finished differently from one that says it was cut off.
            task.setTaskCompletedWithSuccess(outcome != null && !stopped)
        }
    }

    private const val AN_HOUR = 60.0 * 60.0
}
