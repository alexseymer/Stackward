package dev.stackward.logs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.stackward.di.AppContainer
import java.util.concurrent.TimeUnit

class LogDigestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val container = AppContainer(applicationContext)
            val profile = container.profileRepository.loadAll().firstOrNull()
                ?: return Result.success()

            val digest = container.logReader.readDigest(profile)
            container.logDigestStore.save(digest)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "stackward_hourly_log_digest"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LogDigestWorker>(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
