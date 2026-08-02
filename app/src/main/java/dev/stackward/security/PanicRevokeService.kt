package dev.stackward.security

import dev.stackward.connection.ConnectionHealthRepository
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.AgentKeyManager
import dev.stackward.onboarding.ServerProfile
import dev.stackward.onboarding.ServerProfileRepository
import dev.stackward.permissions.AuditEntry
import dev.stackward.permissions.AuditLogRepository
import dev.stackward.permissions.PermissionTier
import dev.stackward.permissions.Tier1RulesRepository

data class PanicRevokeResult(
    val serverMessage: String,
    val auditExport: String,
)

/**
 * Emergency revoke: clear server authorized_keys and wipe local credentials.
 */
class PanicRevokeService(
    private val ssh: SshConnectionManager,
    private val keyManager: AgentKeyManager,
    private val securitySettings: SecuritySettingsRepository,
    private val profileRepository: ServerProfileRepository,
    private val pinStore: HostKeyPinStore,
    private val auditLog: AuditLogRepository,
    private val tier1RulesRepository: Tier1RulesRepository,
    private val connectionHealth: ConnectionHealthRepository,
) {

    suspend fun revoke(profile: ServerProfile): PanicRevokeResult {
        val auditExport = auditLog.exportJson()

        auditLog.append(
            AuditEntry(
                timestamp = System.currentTimeMillis(),
                tier = PermissionTier.BOUNDARY_CHANGE,
                command = "panic_revoke",
                approved = true,
                output = null,
                reason = "Emergency revoke initiated",
            ),
        )

        val serverMessage = runCatching {
            ssh.executeWithRetry(
                profile = profile,
                command = "sudo /usr/local/sbin/stackward-panic-revoke",
                keyAlias = securitySettings.getActiveKeyAlias(),
            )
        }.getOrElse { error ->
            "Server revoke may have failed (${error.message}); wiping local credentials anyway."
        }

        wipeLocalState()
        return PanicRevokeResult(
            serverMessage = serverMessage,
            auditExport = auditExport,
        )
    }

    fun wipeLocalState() {
        keyManager.deleteKeypair(AgentKeyManager.KEY_ALIAS)
        keyManager.deleteKeypair(AgentKeyManager.KEY_ALIAS_ALT)
        profileRepository.clearAll()
        pinStore.clearAll()
        tier1RulesRepository.resetToDefaults()
        connectionHealth.clearAll()
        securitySettings.clear()
        auditLog.clear()
    }
}
