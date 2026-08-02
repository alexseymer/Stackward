package dev.stackward.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.stackward.crypto.AgentKeyManager

/**
 * Security-related preferences: active key alias, Tier 1 review timestamps.
 */
class SecuritySettingsRepository(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

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

    fun getLastTier1ReviewAt(): Long? {
        val value = prefs.getLong(KEY_LAST_TIER1_REVIEW, 0L)
        return value.takeIf { it > 0L }
    }

    fun setLastTier1ReviewAt(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_TIER1_REVIEW, timestamp).apply()
    }

    fun isTier1ReviewDue(): Boolean {
        val last = getLastTier1ReviewAt() ?: return true
        return System.currentTimeMillis() - last > TIER1_REVIEW_INTERVAL_MS
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "stackward_security_settings"
        private const val KEY_ACTIVE_ALIAS = "active_key_alias"
        private const val KEY_LAST_TIER1_REVIEW = "last_tier1_review_at"
        const val TIER1_REVIEW_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
