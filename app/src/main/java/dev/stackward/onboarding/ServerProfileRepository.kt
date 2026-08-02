package dev.stackward.onboarding

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists provisioned [ServerProfile] entries locally.
 */
class ServerProfileRepository(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(profile: ServerProfile) {
        val profiles = loadAll().toMutableList()
        profiles.removeAll { it.id == profile.id }
        profiles.add(profile)
        persist(profiles)
    }

    fun loadAll(): List<ServerProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).toProfile())
            }
        }
    }

    fun hasProvisionedHost(): Boolean = loadAll().isNotEmpty()

    private fun persist(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.forEach { profile -> array.put(profile.toJson()) }
        prefs.edit()
            .putString(KEY_PROFILES, array.toString())
            .apply()
    }

    private fun ServerProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("host", host)
        put("port", port)
        put("hostType", hostType.name)
        put("hostKeyFingerprint", hostKeyFingerprint)
        put("jumpHost", jumpHost)
        put("proxmoxPort", proxmoxPort)
        put("provisionedAt", provisionedAt)
    }

    private fun JSONObject.toProfile(): ServerProfile = ServerProfile(
        id = getString("id"),
        host = getString("host"),
        port = getInt("port"),
        hostType = HostType.valueOf(getString("hostType")),
        hostKeyFingerprint = getString("hostKeyFingerprint"),
        jumpHost = optString("jumpHost").ifBlank { null },
        proxmoxPort = optInt("proxmoxPort", 8006),
        provisionedAt = getLong("provisionedAt"),
    )

    companion object {
        private const val PREFS_NAME = "stackward_server_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}
