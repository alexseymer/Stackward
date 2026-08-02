package dev.stackward.permissions

import dev.stackward.proxmox.ProxmoxCommands

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
 */
class PermissionEngine(
    tier1Rules: List<String> = Tier1RulesRepository.DEFAULT_RULES,
) {

    private var tier1Rules: List<String> = tier1Rules.ifEmpty { Tier1RulesRepository.DEFAULT_RULES }

    fun updateRules(rules: List<String>) {
        tier1Rules = rules.ifEmpty { Tier1RulesRepository.DEFAULT_RULES }
    }

    fun currentTier1Rules(): List<String> = tier1Rules

    fun evaluate(proposal: ActionProposal): PermissionDecision {
        return when (proposal.tier) {
            PermissionTier.ROUTINE -> {
                if (isAllowedRoutine(proposal)) {
                    PermissionDecision.Allow(proposal)
                } else {
                    PermissionDecision.Deny(
                        proposal,
                        "Command not in Tier 1 allowlist: ${proposal.command}",
                    )
                }
            }
            PermissionTier.ONE_TIMER -> {
                if (isValidOneTimerCommand(proposal)) {
                    PermissionDecision.RequireConfirmation(proposal)
                } else {
                    PermissionDecision.Deny(
                        proposal,
                        "Tier 2 command not allowed: ${proposal.command}",
                    )
                }
            }
            PermissionTier.BOUNDARY_CHANGE -> PermissionDecision.DraftOnly(
                proposal,
                suggestedDiff = buildSudoersDiff(proposal),
            )
        }
    }

    fun buildSudoersDiff(proposal: ActionProposal): String {
        return buildString {
            appendLine("# Proposed sudoers.d addition — apply manually via visudo")
            appendLine("# Reason: ${proposal.reason}")
            append("gemma-agent ALL=(root) NOPASSWD: ${proposal.command}")
        }
    }

    private fun isAllowedRoutine(proposal: ActionProposal): Boolean {
        if (proposal.action in READ_ONLY_ACTIONS) return true
        if (proposal.backend == ActionBackend.PROXMOX_API) {
            return ProxmoxCommands.isTier1Read(proposal.command) &&
                !ProxmoxCommands.isTier3Blocked(proposal.command)
        }
        if (proposal.backend != ActionBackend.SSH) return false
        return tier1Rules.any { rule -> proposal.command.startsWith(rule) }
    }

    private fun isValidOneTimerCommand(proposal: ActionProposal): Boolean {
        if (proposal.backend == ActionBackend.PROXMOX_API) {
            return ProxmoxCommands.isTier2Power(proposal.command) &&
                !ProxmoxCommands.isTier3Blocked(proposal.command)
        }
        return ONE_TIMER_PREFIXES.any { prefix -> proposal.command.startsWith(prefix) }
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

        private val ONE_TIMER_PREFIXES = listOf(
            "/usr/bin/systemctl restart ",
            "/bin/systemctl restart ",
            "/usr/bin/systemctl status ",
            "/bin/systemctl status ",
        )
    }
}
