package dev.stackward.permissions

import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPackTest {

    private val engine = PermissionEngine()
    private val oneTimerProposal = ActionProposal(
        tier = PermissionTier.ONE_TIMER,
        action = "restart_service",
        command = "/usr/bin/systemctl restart nginx",
        reason = "502 errors",
    )
    private val routineProposal = ActionProposal(
        tier = PermissionTier.ROUTINE,
        action = "read_journal",
        command = "journalctl -n 1",
        reason = "inspect logs",
    )

    @Test
    fun monitor_deniesOneTimerProposals() {
        val decision = engine.evaluate(oneTimerProposal, CapabilityPack.MONITOR)
        assertTrue(decision is PermissionDecision.Deny)
    }

    @Test
    fun monitor_allowsRoutineProposals() {
        val decision = engine.evaluate(routineProposal, CapabilityPack.MONITOR)
        assertTrue(decision is PermissionDecision.Allow)
    }
}
