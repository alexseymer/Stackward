package dev.stackward.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Creates [EncryptedSharedPreferences], recovering from a corrupted Android Keystore
 * master key by wiping the prefs file and master key alias once.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun create(context: Context, fileName: String): SharedPreferences {
        return try {
            createInternal(context, fileName)
        } catch (error: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences create failed for $fileName — resetting", error)
            reset(context, fileName)
            createInternal(context, fileName)
        }
    }

    private fun createInternal(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun reset(context: Context, fileName: String) {
        runCatching { context.deleteSharedPreferences(fileName) }
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
    }
}
