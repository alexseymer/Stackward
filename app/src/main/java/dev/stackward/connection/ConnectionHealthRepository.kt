package dev.stackward.connection

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/**
 * Tracks last successful / failed SSH connection per server profile.
 */
class ConnectionHealthRepository(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun recordSuccess(profileId: String) {
        prefs.edit()
            .putLong(successKey(profileId), System.currentTimeMillis())
            .remove(failureKey(profileId))
            .remove(errorKey(profileId))
            .apply()
    }

    fun recordFailure(profileId: String, message: String?) {
        prefs.edit()
            .putLong(failureKey(profileId), System.currentTimeMillis())
            .putString(errorKey(profileId), message)
            .apply()
    }

    fun getLastSuccessAt(profileId: String): Long? {
        return prefs.getLong(successKey(profileId), 0L).takeIf { it > 0L }
    }

    fun getLastFailureAt(profileId: String): Long? {
        return prefs.getLong(failureKey(profileId), 0L).takeIf { it > 0L }
    }

    fun getLastError(profileId: String): String? {
        return prefs.getString(errorKey(profileId), null)
    }

    fun clear(profileId: String) {
        prefs.edit()
            .remove(successKey(profileId))
            .remove(failureKey(profileId))
            .remove(errorKey(profileId))
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun successKey(profileId: String) = "success_$profileId"
    private fun failureKey(profileId: String) = "failure_$profileId"
    private fun errorKey(profileId: String) = "error_$profileId"

    companion object {
        private const val PREFS_NAME = "stackward_connection_health"
    }
}
