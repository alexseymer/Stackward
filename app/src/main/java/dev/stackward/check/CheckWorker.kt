package dev.stackward.check

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.stackward.StackwardApplication
import java.util.concurrent.TimeUnit

/**
 * Runs check.sh for a single host on a schedule. Scheduling is per-host (unlike
 * [dev.stackward.logs.LogDigestWorker]'s single global unique work name) because
 * polling cadence is a per-host setting — see [HostPollingRepository].
 */
class CheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.failure()
        return try {
            val container = (applicationContext as StackwardApplication).container
            val profile = container.profileRepository.loadAll().firstOrNull { it.id == profileId }
                ?: return Result.success() // host removed since scheduling; nothing to retry for

            when (val outcome = container.checkScriptRunner.run(profile)) {
                is CheckRunResult.Success -> {
                    container.checkResultStore.save(profileId, outcome.result)
                    Result.success()
                }
                is CheckRunResult.Failure -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_PROFILE_ID = "profile_id"
        private const val POLL_INTERVAL_HOURS = 4L

        private fun workName(profileId: String) = "stackward_check_$profileId"

        fun scheduleAuto4h(context: Context, profileId: String) {
            val request = PeriodicWorkRequestBuilder<CheckWorker>(POLL_INTERVAL_HOURS, TimeUnit.HOURS)
                .setInputData(workDataOf(KEY_PROFILE_ID to profileId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                workName(profileId),
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, profileId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(profileId))
        }
    }
}
