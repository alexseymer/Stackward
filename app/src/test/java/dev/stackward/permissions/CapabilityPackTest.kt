package dev.stackward.permissions

import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPackTest {

    private val engine = PermissionEngine()
    private val tier2Proposal = ActionProposal(
        tier = PermissionTier.ONE_TIMER,
        action = "restart_service",
        command = "/usr/bin/systemctl restart nginx",
        reason = "502 errors",
    )

    @Test
    fun monitor_deniesTier2Proposals() {
        val decision = engine.evaluate(tier2Proposal, CapabilityPack.MONITOR)
        assertTrue(decision is PermissionDecision.Deny)
    }

    @Test
    fun maintain_allowsTier2WhenCommandValid() {
        val decision = engine.evaluate(tier2Proposal, CapabilityPack.MAINTAIN)
        assertTrue(decision is PermissionDecision.RequireConfirmation)
    }

    @Test
    fun provision_deniesAllProposalsInV1() {
        val readProposal = ActionProposal(
            tier = PermissionTier.ROUTINE,
            action = "read_journal",
            command = "journalctl -n 1",
            reason = "inspect",
        )
        val decision = engine.evaluate(readProposal, CapabilityPack.PROVISION)
        assertTrue(decision is PermissionDecision.Deny)
    }
}
