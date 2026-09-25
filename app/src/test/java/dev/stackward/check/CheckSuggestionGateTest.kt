package dev.stackward.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckSuggestionGateTest {

    @Test
    fun resolve_safeActionAutoApproves() {
        val suggestion = CheckSuggestion("id", risk = "safe", action = "tail_journal", reason = "r")

        val decision = CheckSuggestionGate.resolve(suggestion)

        assertTrue(decision is SuggestionDecision.AutoApprove)
        assertEquals("journalctl -n 100 --no-pager", (decision as SuggestionDecision.AutoApprove).command)
    }

    @Test
    fun resolve_riskyActionRequiresConfirmation() {
        val suggestion = CheckSuggestion("id", risk = "risky", action = "cleanup_old_logs", reason = "r")

        val decision = CheckSuggestionGate.resolve(suggestion)

        assertTrue(decision is SuggestionDecision.RequireConfirmation)
    }

    @Test
    fun resolve_scaryActionIsManualOnly() {
        val suggestion = CheckSuggestion("id", risk = "risky", action = "disable_ssh_password_auth", reason = "r")

        val decision = CheckSuggestionGate.resolve(suggestion)

        assertTrue(decision is SuggestionDecision.ManualOnly)
    }

    @Test
    fun resolve_ignoresHostClaimedRiskForUnknownAction() {
        // A compromised or buggy check.sh claims "safe" for an action this device
        // doesn't recognize. The catalog — not the host — decides; unknown actions
        // must never auto-execute regardless of the claimed risk.
        val suggestion = CheckSuggestion("id", risk = "safe", action = "rm_rf_everything", reason = "r")

        val decision = CheckSuggestionGate.resolve(suggestion)

        assertTrue(decision is SuggestionDecision.ManualOnly)
    }

    @Test
    fun resolve_ignoresHostClaimedRiskDowngradingRiskyToSafe() {
        // Host claims "safe" for an action this device catalogs as RISKY — the
        // catalog's classification must win, not the host's.
        val suggestion = CheckSuggestion("id", risk = "safe", action = "cleanup_old_logs", reason = "r")

        val decision = CheckSuggestionGate.resolve(suggestion)

        assertTrue(decision is SuggestionDecision.RequireConfirmation)
    }
}
