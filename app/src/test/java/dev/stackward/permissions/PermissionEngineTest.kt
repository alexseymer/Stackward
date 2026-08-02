package dev.stackward.permissions

import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionEngineTest {

    private val engine = PermissionEngine()

    @Test
    fun evaluate_allowsReadOnlyActionsWithoutCommandMatch() {
        val proposal = ActionProposal(
            tier = PermissionTier.ROUTINE,
            action = "read_journal",
            command = "journalctl -n 1",
            reason = "inspect logs",
        )

        val decision = engine.evaluate(proposal)

        assertTrue(decision is PermissionDecision.Allow)
    }

    @Test
    fun evaluate_allowsProxmoxTier1Get() {
        val proposal = ActionProposal(
            tier = PermissionTier.ROUTINE,
            action = "get_vm_status",
            command = "GET nodes/pve/qemu",
            reason = "list vms",
            backend = ActionBackend.PROXMOX_API,
        )

        val decision = engine.evaluate(proposal)

        assertTrue(decision is PermissionDecision.Allow)
    }

    @Test
    fun evaluate_requiresConfirmationForProxmoxPowerAction() {
        val proposal = ActionProposal(
            tier = PermissionTier.ONE_TIMER,
            action = "vm_reboot",
            command = "POST nodes/pve/qemu/100/status/reboot",
            reason = "guest hung",
            backend = ActionBackend.PROXMOX_API,
        )

        val decision = engine.evaluate(proposal)

        assertTrue(decision is PermissionDecision.RequireConfirmation)
    }

    @Test
    fun evaluate_deniesProxmoxConfigAsTier2() {
        val proposal = ActionProposal(
            tier = PermissionTier.ONE_TIMER,
            action = "vm_config",
            command = "POST nodes/pve/qemu/100/config",
            reason = "attempt privilege change",
            backend = ActionBackend.PROXMOX_API,
        )

        val decision = engine.evaluate(proposal)

        assertTrue(decision is PermissionDecision.Deny)
    }
}
