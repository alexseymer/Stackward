package dev.stackward.security

import dev.stackward.connection.SshConnectionManager
import dev.stackward.crypto.AgentKeyManager
import dev.stackward.crypto.OpenSshPublicKeyEncoder
import dev.stackward.onboarding.ServerProfile
import dev.stackward.permissions.AuditEntry
import dev.stackward.permissions.AuditLogRepository
import dev.stackward.permissions.PermissionTier

data class KeyRotationResult(
    val newPublicKeyLine: String,
    val message: String,
)

/**
 * Rotates the agent SSH key without re-bootstrap: push new key, verify, revoke old.
 */
class KeyRotationService(
    private val keyManager: AgentKeyManager,
    private val securitySettings: SecuritySettingsRepository,
    private val ssh: SshConnectionManager,
    private val auditLog: AuditLogRepository,
) {

    suspend fun rotate(profile: ServerProfile): KeyRotationResult {
        val activeAlias = securitySettings.getActiveKeyAlias()
        val inactiveAlias = securitySettings.getInactiveKeyAlias()

        require(keyManager.hasKeypair(activeAlias)) {
            "No active SSH key found on device"
        }

        val oldPublicLine = keyManager.getPublicKeyOpenSSH(activeAlias)
        val oldMarker = OpenSshPublicKeyEncoder.keyMarker(oldPublicLine)

        if (keyManager.hasKeypair(inactiveAlias)) {
            keyManager.deleteKeypair(inactiveAlias)
        }
        keyManager.generateKeypair(inactiveAlias, replaceExisting = false)

        val newPublicLine = keyManager.getPublicKeyOpenSSH(inactiveAlias)
        val quotedNewKey = shellSingleQuote(newPublicLine)

        ssh.executeWithRetry(
            profile = profile,
            command = "sudo /usr/local/sbin/stackward-push-key $quotedNewKey",
            keyAlias = activeAlias,
        )

        val verify = ssh.verifyAgentConnection(
            host = profile.host,
            port = profile.port,
            expectedFingerprint = profile.hostKeyFingerprint,
            keyAlias = inactiveAlias,
            jumpHost = profile.jumpHost,
            jumpHostPort = profile.jumpHostPort,
            jumpHostKeyFingerprint = profile.jumpHostKeyFingerprint,
        )
        if (!verify.isSuccess || !verify.stdout.contains(SshConnectionManager.AGENT_USERNAME)) {
            throw IllegalStateException(
                "New key verification failed: ${verify.stderr.ifBlank { verify.stdout }}",
            )
        }

        val quotedMarker = shellSingleQuote(oldMarker)
        ssh.executeWithRetry(
            profile = profile,
            command = "sudo /usr/local/sbin/stackward-revoke-key $quotedMarker",
            keyAlias = inactiveAlias,
        )

        keyManager.deleteKeypair(activeAlias)
        securitySettings.setActiveKeyAlias(inactiveAlias)

        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            tier = PermissionTier.BOUNDARY_CHANGE,
            command = "rotate_ssh_key",
            approved = true,
            output = "Old marker revoked: $oldMarker",
            reason = "SSH key rotation completed",
        )
        auditLog.append(entry)

        return KeyRotationResult(
            newPublicKeyLine = newPublicLine,
            message = "Key rotated. Old key revoked on server.",
        )
    }

    private fun shellSingleQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
