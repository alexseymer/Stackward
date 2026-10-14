package dev.stackward.check

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/** Persists the latest check.sh result per host. */
class CheckResultStore(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun save(profileId: String, result: CheckResult) {
        prefs.edit()
            .putString(resultKey(profileId), CheckScriptParser.toJsonString(result))
            .putLong(checkedAtKey(profileId), System.currentTimeMillis())
            .apply()
    }

    fun load(profileId: String): CheckResult? {
        val raw = prefs.getString(resultKey(profileId), null) ?: return null
        return runCatching { CheckScriptParser.parse(raw) }.getOrNull()
    }

    fun getLastCheckedAt(profileId: String): Long? =
        prefs.getLong(checkedAtKey(profileId), 0L).takeIf { it > 0L }

    fun clear(profileId: String) {
        prefs.edit()
            .remove(resultKey(profileId))
            .remove(checkedAtKey(profileId))
            .apply()
    }

    private fun resultKey(profileId: String) = "result_$profileId"
    private fun checkedAtKey(profileId: String) = "checked_at_$profileId"

    companion object {
        private const val PREFS_NAME = "stackward_check_results"
    }
}
