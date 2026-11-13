package dev.stackward.security

import android.content.Context
import dev.stackward.crypto.SecurePrefs
import dev.stackward.crypto.AgentKeyManager

/**
 * Security-related preferences: the active key alias.
 */
class SecuritySettingsRepository(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun getActiveKeyAlias(): String {
        return prefs.getString(KEY_ACTIVE_ALIAS, AgentKeyManager.KEY_ALIAS)
            ?: AgentKeyManager.KEY_ALIAS
    }

    fun setActiveKeyAlias(alias: String) {
        prefs.edit().putString(KEY_ACTIVE_ALIAS, alias).apply()
    }

    fun getInactiveKeyAlias(): String {
        val active = getActiveKeyAlias()
        return if (active == AgentKeyManager.KEY_ALIAS) {
            AgentKeyManager.KEY_ALIAS_ALT
        } else {
            AgentKeyManager.KEY_ALIAS
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "stackward_security_settings"
        private const val KEY_ACTIVE_ALIAS = "active_key_alias"
    }
}
