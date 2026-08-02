package dev.stackward.permissions

import android.content.Context
import dev.stackward.crypto.SecurePrefs

/**
 * Tier 1 command prefixes approved for routine execution without confirmation.
 * Synced from server sudoers.d in a future iteration; defaults are seeded locally.
 */
class Tier1RulesRepository(context: Context) {

    private val prefs = SecurePrefs.create(context, PREFS_NAME)

    fun loadRules(): List<String> {
        val stored = prefs.getStringSet(KEY_RULES, null)
        return stored?.toList()?.sorted() ?: DEFAULT_RULES
    }

    fun saveRules(rules: List<String>) {
        prefs.edit()
            .putStringSet(KEY_RULES, rules.toSet())
            .apply()
    }

    fun resetToDefaults() {
        prefs.edit().remove(KEY_RULES).apply()
    }

    companion object {
        private const val PREFS_NAME = "stackward_tier1_rules"
        private const val KEY_RULES = "rules"

        val DEFAULT_RULES = listOf(
            "/usr/bin/systemctl status",
            "/bin/systemctl status",
        )
    }
}
