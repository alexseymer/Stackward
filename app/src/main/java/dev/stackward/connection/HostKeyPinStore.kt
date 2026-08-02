package dev.stackward.connection

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/**
 * Stores TOFU-pinned SSH host key fingerprints per host:port.
 */
class HostKeyPinStore(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun getPin(host: String, port: Int): String? {
        return prefs.getString(pinKey(host, port), null)
    }

    fun savePin(host: String, port: Int, fingerprint: String) {
        prefs.edit()
            .putString(pinKey(host, port), fingerprint)
            .apply()
    }

    fun clearPin(host: String, port: Int) {
        prefs.edit()
            .remove(pinKey(host, port))
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun pinKey(host: String, port: Int): String = "$host:$port"

    companion object {
        private const val PREFS_NAME = "stackward_host_key_pins"
    }
}
