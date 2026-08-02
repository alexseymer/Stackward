package dev.stackward.onboarding

import android.content.Context
import dev.stackward.crypto.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists provisioned [ServerProfile] entries locally.
 */
class ServerProfileRepository(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

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

    fun clearAll() {
        prefs.edit().remove(KEY_PROFILES).apply()
    }

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
        put("jumpHostPort", jumpHostPort)
        put("jumpHostKeyFingerprint", jumpHostKeyFingerprint)
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
        jumpHostPort = optInt("jumpHostPort", 22),
        jumpHostKeyFingerprint = optString("jumpHostKeyFingerprint").ifBlank { null },
        proxmoxPort = optInt("proxmoxPort", 8006),
        provisionedAt = getLong("provisionedAt"),
    )

    companion object {
        private const val PREFS_NAME = "stackward_server_profiles"
        private const val KEY_PROFILES = "profiles"
    }
}
