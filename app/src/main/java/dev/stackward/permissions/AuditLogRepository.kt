package dev.stackward.permissions

import android.content.Context
import dev.stackward.crypto.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local audit trail for permission decisions and command execution.
 */
class AuditLogRepository(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun append(entry: AuditEntry) {
        val entries = loadAll().toMutableList()
        entries.add(entry)
        val trimmed = entries.takeLast(MAX_ENTRIES)
        persist(trimmed)
    }

    fun loadAll(): List<AuditEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                add(array.getJSONObject(index).toEntry())
            }
        }
    }

    fun exportJson(): String {
        val array = JSONArray()
        loadAll().forEach { entry -> array.put(entry.toJson()) }
        return array.toString(2)
    }

    fun exportNdjson(): String {
        return loadAll().joinToString("\n") { entry -> entry.toJson().toString() }
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun persist(entries: List<AuditEntry>) {
        val array = JSONArray()
        entries.forEach { entry -> array.put(entry.toJson()) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun AuditEntry.toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("tier", tier.name)
        put("command", command)
        put("approved", approved)
        put("output", output)
        put("reason", reason)
    }

    private fun JSONObject.toEntry(): AuditEntry = AuditEntry(
        timestamp = getLong("timestamp"),
        tier = PermissionTier.valueOf(getString("tier")),
        command = getString("command"),
        approved = getBoolean("approved"),
        output = optString("output").ifBlank { null },
        reason = optString("reason").ifBlank { null },
    )

    companion object {
        private const val PREFS_NAME = "stackward_audit_log"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 200
    }
}
