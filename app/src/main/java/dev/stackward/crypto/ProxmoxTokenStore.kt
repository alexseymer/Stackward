package dev.stackward.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores a Proxmox API token alongside the SSH key, same biometric gate.
 */
class ProxmoxTokenStore(
    context: Context,
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun storeToken(tokenId: String, tokenSecret: String) {
        // TODO: Phase 1 — gate writes behind biometric prompt
        prefs.edit()
            .putString(KEY_TOKEN_ID, tokenId)
            .putString(KEY_TOKEN_SECRET, tokenSecret)
            .apply()
    }

    fun getToken(): Pair<String, String>? {
        val tokenId = prefs.getString(KEY_TOKEN_ID, null) ?: return null
        val tokenSecret = prefs.getString(KEY_TOKEN_SECRET, null) ?: return null
        return tokenId to tokenSecret
    }

    fun deleteToken() {
        prefs.edit()
            .remove(KEY_TOKEN_ID)
            .remove(KEY_TOKEN_SECRET)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "stackward_proxmox_token"
        private const val KEY_TOKEN_ID = "token_id"
        private const val KEY_TOKEN_SECRET = "token_secret"
    }
}
