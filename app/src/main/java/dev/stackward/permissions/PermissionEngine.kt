package dev.stackward.permissions

/**
 * Classification of an action proposed by the on-device model.
 */
enum class PermissionTier {
    /** Read-only or pre-vetted low-risk. No confirmation needed. */
    ROUTINE,
    /** Named, bounded elevation for a single action. Requires confirmation + biometric. */
    ONE_TIMER,
    /** Changes what the agent is allowed to do. Never automated. */
    BOUNDARY_CHANGE,
}

/**
 * A structured proposal emitted by the model, before any server interaction.
 */
data class ActionProposal(
    val tier: PermissionTier,
    val action: String,
    val command: String,
    val reason: String,
    val target: String? = null,
    val backend: ActionBackend = ActionBackend.SSH,
)

enum class ActionBackend {
    SSH,
    PROXMOX_API,
}

/**
 * Result of the permission engine evaluating a proposal.
 */
sealed class PermissionDecision {
    data class Allow(val proposal: ActionProposal) : PermissionDecision()
    data class RequireConfirmation(val proposal: ActionProposal) : PermissionDecision()
    data class Deny(val proposal: ActionProposal, val reason: String) : PermissionDecision()
    data class DraftOnly(val proposal: ActionProposal, val suggestedDiff: String) : PermissionDecision()
}

/**
 * Record of an executed (or denied) action for the audit log.
 */
data class AuditEntry(
    val timestamp: Long,
    val tier: PermissionTier,
    val command: String,
    val approved: Boolean,
    val output: String?,
    val reason: String?,
)

/**
 * Central safety gate. Every model proposal passes through here.
 *
 * Tier 1 (ROUTINE): match against sudoers.d rules or read-only tools → allow + log.
 * Tier 2 (ONE_TIMER): block until user confirms literal command + biometric.
 * Tier 3 (BOUNDARY_CHANGE): reject from automated path; draft only.
 */
class PermissionEngine(
    private val tier1Rules: List<String> = emptyList(),
) {

    /**
     * Evaluate a model proposal and return the permission decision.
     */
    fun evaluate(proposal: ActionProposal): PermissionDecision {
        return when (proposal.tier) {
            PermissionTier.ROUTINE -> {
                if (isAllowedRoutine(proposal)) {
                    PermissionDecision.Allow(proposal)
                } else {
                    PermissionDecision.Deny(
                        proposal,
                        "Command not in Tier 1 allowlist: ${proposal.command}"
                    )
                }
            }
            PermissionTier.ONE_TIMER -> PermissionDecision.RequireConfirmation(proposal)
            PermissionTier.BOUNDARY_CHANGE -> PermissionDecision.DraftOnly(
                proposal,
                suggestedDiff = buildSudoersDiff(proposal)
            )
        }
    }

    /**
     * Execute a confirmed Tier 2 action with temporary sudoers grant.
     * Called only after user approval + biometric.
     */
    suspend fun executeOneTimer(
        proposal: ActionProposal,
        sshExecutor: suspend (command: String) -> String,
    ): AuditEntry {
        // TODO: Phase 3
        // 1. Write temporary single-use sudoers.d rule
        // 2. Execute command via SSH
        // 3. Delete sudoers.d rule immediately
        // 4. Return audit entry
        TODO("Phase 3: temporary sudoers grant + execute + cleanup")
    }

    private fun isAllowedRoutine(proposal: ActionProposal): Boolean {
        // Read-only actions are always Tier 1
        if (proposal.action in READ_ONLY_ACTIONS) return true
        // Check against sudoers.d rules
        return tier1Rules.any { rule -> proposal.command.startsWith(rule) }
    }

    private fun buildSudoersDiff(proposal: ActionProposal): String {
        // TODO: generate a human-readable diff for Tier 3 draft
        return "# Proposed sudoers.d addition (apply manually via visudo):\n" +
            "# ${proposal.command}"
    }

    companion object {
        val READ_ONLY_ACTIONS = setOf(
            "read_journal",
            "read_docker_logs",
            "list_containers",
            "get_vm_status",
            "get_lxc_status",
            "read_proxmox_tasks",
        )
    }
}
