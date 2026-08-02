package dev.stackward.permissions

import dev.stackward.onboarding.ServerProfile
import dev.stackward.proxmox.ProxmoxApiClient
import dev.stackward.proxmox.ProxmoxCommands

data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val auditEntry: AuditEntry,
)

/**
 * Executes approved proposals over SSH or Proxmox API with tier-appropriate controls.
 */
class PermissionExecutor(
    private val engine: PermissionEngine,
    private val auditLog: AuditLogRepository,
    private val proxmoxApi: ProxmoxApiClient? = null,
) {

    fun evaluate(proposal: ActionProposal): PermissionDecision {
        return engine.evaluate(proposal)
    }

    suspend fun executeApproved(
        profile: ServerProfile,
        proposal: ActionProposal,
        sshExecutor: suspend (command: String) -> String,
    ): ExecutionResult {
        val decision = engine.evaluate(proposal)
        if (decision is PermissionDecision.Deny) {
            val entry = auditDenied(proposal, decision.reason)
            return ExecutionResult(success = false, output = decision.reason, auditEntry = entry)
        }
        if (decision is PermissionDecision.DraftOnly) {
            val entry = auditDenied(proposal, "Tier 3 draft only — not executed")
            return ExecutionResult(success = false, output = decision.suggestedDiff, auditEntry = entry)
        }

        return when (proposal.backend) {
            ActionBackend.PROXMOX_API -> executeProxmox(profile, proposal)
            ActionBackend.SSH -> when (proposal.tier) {
                PermissionTier.ROUTINE -> executeTier1(profile, proposal, sshExecutor)
                PermissionTier.ONE_TIMER -> executeTier2(profile, proposal, sshExecutor)
                PermissionTier.BOUNDARY_CHANGE -> {
                    val diff = engine.buildSudoersDiff(proposal)
                    val entry = auditDenied(proposal, "Tier 3 blocked")
                    ExecutionResult(success = false, output = diff, auditEntry = entry)
                }
            }
        }
    }

    private suspend fun executeProxmox(
        profile: ServerProfile,
        proposal: ActionProposal,
    ): ExecutionResult {
        val client = proxmoxApi
            ?: return deniedExecution(proposal, "Proxmox API client not configured")

        if (proposal.tier == PermissionTier.BOUNDARY_CHANGE ||
            ProxmoxCommands.isTier3Blocked(proposal.command)
        ) {
            return deniedExecution(proposal, "Tier 3 Proxmox changes are draft-only")
        }

        if (proposal.tier == PermissionTier.ROUTINE) {
            if (proposal.action in PermissionEngine.READ_ONLY_ACTIONS) {
                val entry = auditDenied(proposal, "Read-only Proxmox action — logged only")
                return ExecutionResult(
                    success = true,
                    output = "Read-only Proxmox proposal logged; use digest or on-demand query.",
                    auditEntry = entry,
                )
            }
        }

        val parsed = ProxmoxCommands.parse(proposal.command)
            ?: return deniedExecution(proposal, "Invalid Proxmox API command: ${proposal.command}")

        return runRemote(
            proposal = proposal,
            remoteCommand = proposal.command,
            approved = true,
        ) {
            client.execute(profile, parsed.first, parsed.second)
        }
    }

    private suspend fun executeTier1(
        profile: ServerProfile,
        proposal: ActionProposal,
        sshExecutor: suspend (command: String) -> String,
    ): ExecutionResult {
        if (proposal.action in PermissionEngine.READ_ONLY_ACTIONS) {
            val entry = auditDenied(proposal, "Read-only action — nothing to execute")
            return ExecutionResult(
                success = true,
                output = "Read-only proposal logged; no remote execution required.",
                auditEntry = entry,
            )
        }

        return runRemote(
            proposal = proposal,
            remoteCommand = wrapSudoIfNeeded(proposal.command),
            sshExecutor = sshExecutor,
            approved = true,
        )
    }

    private suspend fun executeTier2(
        profile: ServerProfile,
        proposal: ActionProposal,
        sshExecutor: suspend (command: String) -> String,
    ): ExecutionResult {
        val encoded = OneTimerCommandEncoder.encode(proposal.command)
        val remoteCommand = "sudo /usr/local/sbin/stackward-onetimer $encoded"
        return runRemote(
            proposal = proposal,
            remoteCommand = remoteCommand,
            sshExecutor = sshExecutor,
            approved = true,
        )
    }

    private suspend fun runRemote(
        proposal: ActionProposal,
        remoteCommand: String,
        sshExecutor: suspend (command: String) -> String,
        approved: Boolean,
    ): ExecutionResult {
        return runRemote(proposal, remoteCommand, approved) { sshExecutor(remoteCommand) }
    }

    private suspend fun runRemote(
        proposal: ActionProposal,
        remoteCommand: String,
        approved: Boolean,
        executor: suspend () -> String,
    ): ExecutionResult {
        return try {
            val output = executor()
            val entry = AuditEntry(
                timestamp = System.currentTimeMillis(),
                tier = proposal.tier,
                command = proposal.command,
                approved = approved,
                output = output,
                reason = proposal.reason,
            )
            auditLog.append(entry)
            ExecutionResult(success = true, output = output, auditEntry = entry)
        } catch (error: Exception) {
            val entry = AuditEntry(
                timestamp = System.currentTimeMillis(),
                tier = proposal.tier,
                command = proposal.command,
                approved = approved,
                output = error.message,
                reason = proposal.reason,
            )
            auditLog.append(entry)
            ExecutionResult(success = false, output = error.message ?: "Execution failed", auditEntry = entry)
        }
    }

    private fun deniedExecution(proposal: ActionProposal, reason: String): ExecutionResult {
        val entry = auditDenied(proposal, reason)
        return ExecutionResult(success = false, output = reason, auditEntry = entry)
    }

    private fun auditDenied(proposal: ActionProposal, reason: String): AuditEntry {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            tier = proposal.tier,
            command = proposal.command,
            approved = false,
            output = null,
            reason = reason,
        )
        auditLog.append(entry)
        return entry
    }

    private fun wrapSudoIfNeeded(command: String): String {
        return if (command.startsWith("sudo ")) command else "sudo $command"
    }
}

object OneTimerCommandEncoder {
    fun encode(command: String): String {
        return android.util.Base64.encodeToString(
            command.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
    }
}
