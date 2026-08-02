package dev.stackward.proxmox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxmoxCommandsTest {

    @Test
    fun isTier1Read_allowsNodeListing() {
        assertTrue(ProxmoxCommands.isTier1Read("GET nodes"))
        assertTrue(ProxmoxCommands.isTier1Read("GET nodes/pve/qemu"))
        assertTrue(ProxmoxCommands.isTier1Read("GET /api2/json/nodes/pve/tasks"))
    }

    @Test
    fun isTier1Read_rejectsMutations() {
        assertFalse(ProxmoxCommands.isTier1Read("POST nodes/pve/qemu/100/status/start"))
        assertFalse(ProxmoxCommands.isTier1Read("GET access/users"))
    }

    @Test
    fun isTier2Power_allowsScopedPowerActions() {
        assertTrue(ProxmoxCommands.isTier2Power("POST nodes/pve/qemu/100/status/reboot"))
        assertTrue(ProxmoxCommands.isTier2Power("POST nodes/pve/lxc/200/status/shutdown"))
    }

    @Test
    fun isTier2Power_rejectsConfigChanges() {
        assertFalse(ProxmoxCommands.isTier2Power("POST nodes/pve/qemu/100/config"))
    }

    @Test
    fun isTier3Blocked_flagsPrivilegeChanges() {
        assertTrue(ProxmoxCommands.isTier3Blocked("POST nodes/pve/qemu/100/config"))
        assertTrue(ProxmoxCommands.isTier3Blocked("GET access/roles"))
        assertFalse(ProxmoxCommands.isTier3Blocked("GET nodes/pve/qemu"))
    }
}
