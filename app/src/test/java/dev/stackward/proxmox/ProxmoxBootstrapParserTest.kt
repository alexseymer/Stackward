package dev.stackward.proxmox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxmoxBootstrapParserTest {

    @Test
    fun parse_extractsTokenFromBootstrapMarker() {
        val stdout = """
            ==> Proxmox bootstrap complete.
            STACKWARD_TOKEN_JSON={"data":{"full-tokenid":"gemma-agent@pve!stackward","value":"abc-secret-123"}}
        """.trimIndent()

        val credentials = ProxmoxBootstrapParser.parse(stdout)

        assertEquals("gemma-agent@pve!stackward", credentials?.tokenId)
        assertEquals("abc-secret-123", credentials?.tokenSecret)
    }

    @Test
    fun parse_returnsNullWhenMarkerMissing() {
        assertNull(ProxmoxBootstrapParser.parse("bootstrap finished without token json"))
    }

    @Test
    fun parse_acceptsFlatJsonShape() {
        val stdout = """STACKWARD_TOKEN_JSON={"tokenid":"gemma-agent@pve!stackward","secret":"flat-secret"}"""

        val credentials = ProxmoxBootstrapParser.parse(stdout)

        assertEquals("gemma-agent@pve!stackward", credentials?.tokenId)
        assertEquals("flat-secret", credentials?.tokenSecret)
    }
}
