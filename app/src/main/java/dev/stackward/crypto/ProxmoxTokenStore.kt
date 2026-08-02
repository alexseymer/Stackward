package dev.stackward.crypto

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/**
 * Stores a Proxmox API token alongside the SSH key, same biometric gate.
 */
class ProxmoxTokenStore(
    context: Context,
) {
    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun storeToken(tokenId: String, tokenSecret: String) {
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
