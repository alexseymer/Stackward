package dev.stackward.inference

import dev.stackward.permissions.ActionBackend
import dev.stackward.permissions.PermissionTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionProposalParserTest {

    @Test
    fun parse_readsFencedJsonArray() {
        val output = """
            Here are proposed actions:
            ```json
            [
              {
                "tier": "ROUTINE",
                "action": "get_vm_status",
                "command": "GET nodes/pve/qemu",
                "reason": "status check",
                "backend": "PROXMOX_API"
              }
            ]
            ```
        """.trimIndent()

        val proposals = ActionProposalParser.parse(output)

        assertEquals(1, proposals.size)
        assertEquals(PermissionTier.ROUTINE, proposals[0].tier)
        assertEquals(ActionBackend.PROXMOX_API, proposals[0].backend)
    }

    @Test
    fun parse_returnsEmptyForMissingJson() {
        assertTrue(ActionProposalParser.parse("no structured output here").isEmpty())
    }
}
