package dev.stackward.check

import android.content.Context
import androidx.work.WorkManager

/**
 * Earlier builds scheduled background SSH checks (WorkManager) and notifications. The app
 * no longer opens SSH without the user present (PRD D1), so installs that upgrade from
 * such a build must not keep jobs enqueued for classes that no longer exist.
 *
 * Runs once per install; this is the only remaining WorkManager use.
 */
object LegacyBackgroundCleanup {
    private const val PREFS = "stackward_migrations"
    private const val KEY_DONE = "background_jobs_cancelled_v1"

    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
        context.deleteSharedPreferences("stackward_host_polling")
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}
