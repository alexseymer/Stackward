package dev.stackward.check

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/** Per-host check.sh polling cadence. See PRD.md §5.3 / STRATEGY.md dashboard behavior. */
enum class PollingMode {
    MANUAL,
    AUTO_4H,
}

class HostPollingRepository(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun getMode(profileId: String): PollingMode {
        val raw = prefs.getString(modeKey(profileId), null) ?: return PollingMode.MANUAL
        return runCatching { PollingMode.valueOf(raw) }.getOrDefault(PollingMode.MANUAL)
    }

    fun setMode(profileId: String, mode: PollingMode) {
        prefs.edit().putString(modeKey(profileId), mode.name).apply()
    }

    fun clear(profileId: String) {
        prefs.edit().remove(modeKey(profileId)).apply()
    }

    private fun modeKey(profileId: String) = "mode_$profileId"

    companion object {
        private const val PREFS_NAME = "stackward_host_polling"
    }
}
