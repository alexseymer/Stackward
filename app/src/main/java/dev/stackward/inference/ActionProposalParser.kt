package dev.stackward.inference

import org.json.JSONArray
import org.json.JSONObject
import dev.stackward.permissions.ActionBackend
import dev.stackward.permissions.ActionProposal
import dev.stackward.permissions.PermissionTier

/**
 * Parses structured action proposals from model output.
 *
 * Expected JSON array schema:
 * ```json
 * [
 *   {
 *     "tier": "ROUTINE|ONE_TIMER|BOUNDARY_CHANGE",
 *     "action": "read_journal",
 *     "command": "journalctl ...",
 *     "reason": "why",
 *     "target": "optional",
 *     "backend": "SSH|PROXMOX_API"
 *   }
 * ]
 * ```
 */
object ActionProposalParser {

    fun parse(modelOutput: String): List<ActionProposal> {
        val jsonBlock = extractJsonBlock(modelOutput) ?: return emptyList()
        return runCatching { parseJsonArray(jsonBlock) }.getOrElse { emptyList() }
    }

    private fun extractJsonBlock(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fenced.isNullOrBlank()) return fenced

        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private fun parseJsonArray(json: String): List<ActionProposal> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(item.toProposal())
            }
        }
    }

    private fun JSONObject.toProposal(): ActionProposal {
        return ActionProposal(
            tier = parseTier(getString("tier")),
            action = getString("action"),
            command = getString("command"),
            reason = getString("reason"),
            target = optString("target").ifBlank { null },
            backend = parseBackend(optString("backend")),
        )
    }

    private fun parseTier(raw: String): PermissionTier {
        return when (raw.uppercase()) {
            "ROUTINE", "TIER1", "TIER_1" -> PermissionTier.ROUTINE
            "ONE_TIMER", "TIER2", "TIER_2" -> PermissionTier.ONE_TIMER
            "BOUNDARY_CHANGE", "TIER3", "TIER_3" -> PermissionTier.BOUNDARY_CHANGE
            else -> PermissionTier.ROUTINE
        }
    }

    private fun parseBackend(raw: String): ActionBackend {
        return when (raw.uppercase()) {
            "PROXMOX_API", "PROXMOX" -> ActionBackend.PROXMOX_API
            else -> ActionBackend.SSH
        }
    }
}
