package dev.stackward.check

/** What the app will actually do about a suggestion, per PRD.md §5.1. */
sealed class SuggestionDecision {
    /** [ActionRisk.SAFE]: runs immediately, no confirmation. */
    data class AutoApprove(val command: String) : SuggestionDecision()

    /** [ActionRisk.RISKY]: show the literal command, require biometric confirmation. */
    data class RequireConfirmation(val command: String) : SuggestionDecision()

    /** [ActionRisk.SCARY] or [ActionRisk.UNKNOWN]: never executed, manual workaround shown. */
    data class ManualOnly(val description: String) : SuggestionDecision()
}

object CheckSuggestionGate {

    /**
     * Resolves a suggestion using this device's own [CheckActionCatalog] — not the
     * suggestion's self-reported [CheckSuggestion.risk] string, which is untrusted
     * input from the host.
     */
    fun resolve(suggestion: CheckSuggestion): SuggestionDecision {
        val entry = CheckActionCatalog.lookup(suggestion.action)
        return when (entry.risk) {
            ActionRisk.SAFE -> SuggestionDecision.AutoApprove(
                requireNotNull(entry.command) { "SAFE catalog entry must have a command" },
            )
            ActionRisk.RISKY -> SuggestionDecision.RequireConfirmation(
                requireNotNull(entry.command) { "RISKY catalog entry must have a command" },
            )
            ActionRisk.SCARY, ActionRisk.UNKNOWN -> SuggestionDecision.ManualOnly(entry.description)
        }
    }
}
