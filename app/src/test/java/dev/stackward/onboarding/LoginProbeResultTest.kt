package dev.stackward.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginProbeResultTest {

    @Test
    fun parse_restrictedUser() {
        val result = LoginProbeResult.parse(
            """
            stackward-agent
            stackward-agent systemd-journal
            HOME=/home/stackward-agent
            sudo=no
            """.trimIndent(),
        )
        assertEquals("stackward-agent", result.username)
        assertFalse(result.isRoot)
        assertFalse(result.canPasswordlessSudo)
        assertFalse(result.isElevated)
    }

    @Test
    fun parse_passwordlessSudo() {
        val result = LoginProbeResult.parse(
            """
            alex
            alex sudo
            HOME=/home/alex
            sudo=yes
            """.trimIndent(),
        )
        assertTrue(result.canPasswordlessSudo)
        assertTrue(result.isElevated)
        assertTrue(result.displayText.contains("STACKWARD_PRIVILEGE=elevated"))
    }

    @Test
    fun parse_root() {
        val result = LoginProbeResult.parse(
            """
            root
            root
            HOME=/root
            sudo=no
            """.trimIndent(),
        )
        assertTrue(result.isRoot)
        assertTrue(result.isElevated)
    }
}
