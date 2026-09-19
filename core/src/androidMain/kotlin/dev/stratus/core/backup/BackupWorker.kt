package dev.stratus.core.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.stratus.core.appContainer
import java.util.concurrent.TimeUnit

/**
 * When the backup runs, and how it stays alive while it does.
 *
 * **It executes and decides nothing.** What goes next, what is retried and when
 * to give up are [UploadQueue]'s, and they are there because this is the half
 * that runs where no test can watch it. What is decided here is only how to
 * survive Android: a foreground service for the duration, because background
 * work that is not in the foreground is killed by OEM battery managers however
 * correct it is, and stopping between files when the system asks.
 */
class BackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val backup = appContainer(applicationContext).backup
        if (backup.enabled().isEmpty()) return Result.success()

        setForeground(getForegroundInfo())

        val outcome = backup.pass(
            // Asked between files, so being reclaimed costs nothing: the queue
            // is in the database and picks up where it stopped.
            keepGoing = { !isStopped },
            onStep = { step ->
                if (step is QueueStep.Uploaded) {
                    setForegroundAsync(notify(step.path.substringAfterLast('/')))
                }
            },
        )

        // Coming back later is WorkManager's job; whether there is a reason to
        // is the queue's, and which item is ready is the queue's too. The two
        // backoffs are about different things and do not fight.
        return if (outcome == PassOutcome.ComeBackLater) Result.retry() else Result.success()
    }

    /**
     * Overridden so the notification -- and the channel it needs, which throws
     * on modern Android if it is missing -- can be built by a test without
     * running a backup.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = notify("Looking for new photographs")

    private fun notify(detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Backing up")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION, notification)
        }
    }

    companion object {
        private const val CHANNEL = "backup"
        private const val NOTIFICATION = 1
        private const val PERIODIC = "stratus-backup"
        private const val ONCE = "stratus-backup-now"

        /**
         * Asks Android to come back regularly.
         *
         * Six hours rather than the fifteen-minute floor: this is a backup and
         * not a chat client, and a phone that wakes an app four times an hour is
         * a phone whose owner turns the app off.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        }

        /** Runs a pass now, for somebody who does not want to wait six hours. */
        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                androidx.work.ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BackupWorker>().build(),
            )
        }
    }
}
