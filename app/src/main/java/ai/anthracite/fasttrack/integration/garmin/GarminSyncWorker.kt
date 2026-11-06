package ai.anthracite.fasttrack.integration.garmin

import ai.anthracite.fasttrack.FastTrackApp
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps Garmin wellness + activity data fresh even when the app isn't open. The in-app foreground
 * loop handles near-real-time refresh while the user is active; this worker covers the gaps so the
 * daily update is ready the next time they open the app. No-op when Garmin isn't configured.
 */
class GarminSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FastTrackApp ?: return Result.success()
        if (!app.garminClient.isConfigured) return Result.success()
        return runCatching { app.syncGarminNow() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val WORK_NAME = "garmin_background_sync"

        /** Schedules the periodic background pull once; subsequent calls keep the existing work. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GarminSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
