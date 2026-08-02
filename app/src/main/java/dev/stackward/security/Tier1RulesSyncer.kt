package dev.stackward.security

import dev.stackward.connection.SshConnectionManager
import dev.stackward.onboarding.ServerProfile
import dev.stackward.permissions.PermissionEngine
import dev.stackward.permissions.Tier1RulesRepository

data class Tier1SyncResult(
    val serverRules: List<String>,
    val localRules: List<String>,
    val onlyOnServer: List<String>,
    val onlyLocal: List<String>,
)

/**
 * Fetches sudoers.d snapshot from the server and reconciles local Tier 1 rules.
 */
class Tier1RulesSyncer(
    private val ssh: SshConnectionManager,
    private val tier1RulesRepository: Tier1RulesRepository,
    private val permissionEngine: PermissionEngine,
    private val securitySettings: SecuritySettingsRepository,
) {

    suspend fun syncFromServer(profile: ServerProfile): Tier1SyncResult {
        val raw = ssh.executeWithRetry(profile, "sudo /usr/local/sbin/stackward-sudoers-snapshot")
        val serverRules = parseSudoersSnapshot(raw)
        val localRules = tier1RulesRepository.loadRules()
        val merged = (serverRules + localRules).distinct().sorted()

        tier1RulesRepository.saveRules(merged)
        permissionEngine.updateRules(merged)
        securitySettings.setLastTier1ReviewAt()

        return Tier1SyncResult(
            serverRules = serverRules,
            localRules = localRules,
            onlyOnServer = serverRules.filterNot { localRules.contains(it) },
            onlyLocal = localRules.filterNot { serverRules.contains(it) },
        )
    }

    fun markReviewedWithoutSync() {
        securitySettings.setLastTier1ReviewAt()
    }

    companion object {
        private val NOPASSWD_REGEX = Regex(
            """^gemma-agent\s+ALL=\(root\)\s+NOPASSWD:\s+(.+)$""",
        )

        fun parseSudoersSnapshot(raw: String): List<String> {
            return raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val match = NOPASSWD_REGEX.matchEntire(line) ?: return@mapNotNull null
                    val command = match.groupValues[1].trim()
                    if (command.startsWith("/usr/local/sbin/stackward-")) {
                        null
                    } else {
                        command.removeSuffix(" *").trim()
                    }
                }
                .distinct()
                .sorted()
                .toList()
        }
    }
}
