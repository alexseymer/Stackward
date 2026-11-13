package dev.stackward.connection

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/** Read/write access to pinned host-key fingerprints (an interface so verification is unit-testable). */
interface HostKeyPins {
    fun getPin(host: String, port: Int): String?
    fun savePin(host: String, port: Int, fingerprint: String)
}

/**
 * Stores TOFU-pinned SSH host key fingerprints per host:port.
 */
class HostKeyPinStore(context: Context) : HostKeyPins {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    override fun getPin(host: String, port: Int): String? {
        return prefs.getString(pinKey(host, port), null)
    }

    override fun savePin(host: String, port: Int, fingerprint: String) {
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
