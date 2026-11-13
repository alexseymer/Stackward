package dev.stackward.security

import android.content.Context
import dev.stackward.check.CheckResultStore
import dev.stackward.connection.ConnectionHealthRepository
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.AgentKeyManager
import dev.stackward.onboarding.ServerProfile
import dev.stackward.onboarding.ServerProfileRepository
import dev.stackward.permissions.AuditEntry
import dev.stackward.permissions.AuditLogRepository
import dev.stackward.permissions.PermissionTier

data class PanicRevokeResult(
    val serverMessage: String,
    val auditExport: String,
)

/**
 * Emergency revoke: clear server authorized_keys and wipe local credentials.
 */
class PanicRevokeService(
    private val context: Context,
    private val ssh: SshConnectionManager,
    private val keyManager: AgentKeyManager,
    private val securitySettings: SecuritySettingsRepository,
    private val profileRepository: ServerProfileRepository,
    private val pinStore: HostKeyPinStore,
    private val auditLog: AuditLogRepository,
    private val connectionHealth: ConnectionHealthRepository,
    private val checkResultStore: CheckResultStore,
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
        // Read profiles before clearAll() wipes them — per-host check.sh state is keyed by profile id and have no other way to enumerate.
        val profiles = profileRepository.loadAll()
        profiles.forEach { profile ->
            checkResultStore.clear(profile.id)
        }

        keyManager.deleteKeypair(AgentKeyManager.KEY_ALIAS)
        keyManager.deleteKeypair(AgentKeyManager.KEY_ALIAS_ALT)
        profileRepository.clearAll()
        pinStore.clearAll()
        connectionHealth.clearAll()
        securitySettings.clear()
        auditLog.clear()
    }
}
