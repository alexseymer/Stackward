package dev.stackward.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingUiStateTest {

    private fun baseState(
        useJumpHost: Boolean = false,
        jumpHost: String = "",
        jumpHostPort: String = "22",
    ) = OnboardingUiState(
        host = "10.0.0.5",
        port = "22",
        adminUsername = "root",
        adminCredential = "secret",
        publicKeyOpenSsh = "ssh-ed25519 AAAA test@phone",
        useJumpHost = useJumpHost,
        jumpHost = jumpHost,
        jumpHostPort = jumpHostPort,
    )

    @Test
    fun canPreviewScript_withoutJump_whenRequiredFieldsPresent() {
        assertTrue(baseState().canPreviewScript)
        assertNull(baseState().resolvedJumpHost)
    }

    @Test
    fun canPreviewScript_requiresJumpHostWhenEnabled() {
        assertFalse(baseState(useJumpHost = true, jumpHost = "").canPreviewScript)
        assertFalse(baseState(useJumpHost = true, jumpHost = "bastion", jumpHostPort = "").canPreviewScript)
        assertTrue(baseState(useJumpHost = true, jumpHost = "bastion.example", jumpHostPort = "2222").canPreviewScript)
    }

    @Test
    fun resolvedJumpHost_onlyWhenToggleEnabled() {
        val disabled = baseState(useJumpHost = false, jumpHost = "bastion")
        assertNull(disabled.resolvedJumpHost)

        val enabled = baseState(useJumpHost = true, jumpHost = "bastion")
        assertTrue(enabled.resolvedJumpHost == "bastion")
        assertTrue(enabled.resolvedJumpHostPort == 22)
    }
}
