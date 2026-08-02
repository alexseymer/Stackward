package dev.stackward.logs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the latest scheduled log digest for display in the app.
 */
class LogDigestStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(digest: LogDigest) {
        prefs.edit()
            .putString(KEY_CONTENT, digest.content)
            .putBoolean(KEY_TRUNCATED, digest.truncated)
            .putLong(KEY_GENERATED_AT, digest.generatedAt)
            .apply()
    }

    fun load(): LogDigest? {
        val content = prefs.getString(KEY_CONTENT, null) ?: return null
        return LogDigest(
            content = content,
            truncated = prefs.getBoolean(KEY_TRUNCATED, false),
            generatedAt = prefs.getLong(KEY_GENERATED_AT, 0L),
        )
    }

    companion object {
        private const val PREFS_NAME = "stackward_log_digest"
        private const val KEY_CONTENT = "content"
        private const val KEY_TRUNCATED = "truncated"
        private const val KEY_GENERATED_AT = "generated_at"
    }
}
