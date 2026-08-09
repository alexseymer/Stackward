package dev.stackward.ui.onboarding

import dev.stackward.onboarding.BootstrapAuthMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingUiStateTest {

    private fun baseState(
        useJumpHost: Boolean = false,
        jumpHost: String = "",
        jumpHostPort: String = "22",
        authMethod: BootstrapAuthMethod = BootstrapAuthMethod.PASSWORD,
        hasSshPassword: Boolean = true,
        hasPrivateKeyPem: Boolean = false,
        step: ProvisionStep = ProvisionStep.CONFIRM,
    ) = OnboardingUiState(
        host = "10.0.0.5",
        port = "22",
        agentUsername = "stackward-agent",
        authMethod = authMethod,
        hasSshPassword = hasSshPassword,
        hasPrivateKeyPem = hasPrivateKeyPem,
        publicKeyOpenSsh = "ssh-ed25519 AAAA test@phone",
        useJumpHost = useJumpHost,
        jumpHost = jumpHost,
        jumpHostPort = jumpHostPort,
        step = step,
    )

    @Test
    fun canContinueToConfirm_withoutJump_whenRequiredFieldsPresent() {
        assertTrue(baseState(step = ProvisionStep.INPUT).canContinueToConfirm)
        assertNull(baseState().resolvedJumpHost)
    }

    @Test
    fun canContinueToConfirm_requiresJumpHostWhenEnabled() {
        assertFalse(
            baseState(useJumpHost = true, jumpHost = "", step = ProvisionStep.INPUT)
                .canContinueToConfirm,
        )
        assertFalse(
            baseState(useJumpHost = true, jumpHost = "bastion", jumpHostPort = "", step = ProvisionStep.INPUT)
                .canContinueToConfirm,
        )
        assertTrue(
            baseState(
                useJumpHost = true,
                jumpHost = "bastion.example",
                jumpHostPort = "2222",
                step = ProvisionStep.INPUT,
            ).canContinueToConfirm,
        )
    }

    @Test
    fun canContinueToConfirm_acceptsAgentKeyWhenPrivateKeyAuth() {
        assertTrue(
            baseState(
                authMethod = BootstrapAuthMethod.PRIVATE_KEY,
                hasSshPassword = false,
                hasPrivateKeyPem = false,
                step = ProvisionStep.INPUT,
            ).canContinueToConfirm,
        )
        assertTrue(
            baseState(
                authMethod = BootstrapAuthMethod.PRIVATE_KEY,
                hasSshPassword = false,
                hasPrivateKeyPem = false,
                step = ProvisionStep.INPUT,
            ).usingAgentKeyForLogin,
        )
        assertFalse(
            baseState(
                authMethod = BootstrapAuthMethod.PRIVATE_KEY,
                hasSshPassword = false,
                hasPrivateKeyPem = false,
                step = ProvisionStep.INPUT,
            ).copy(publicKeyOpenSsh = null).canContinueToConfirm,
        )
    }

    @Test
    fun canContinueToConfirm_allowsRootUsername() {
        assertTrue(
            baseState(step = ProvisionStep.INPUT)
                .copy(agentUsername = "root")
                .canContinueToConfirm,
        )
    }

    @Test
    fun canStartSetup_requiresElevatedAcknowledgmentWhenDetected() {
        val elevated = baseState(step = ProvisionStep.CONFIRM).copy(
            elevatedPrivilegeDetected = true,
            elevatedPrivilegeAcknowledged = false,
        )
        assertFalse(elevated.canStartSetup)

        assertTrue(
            elevated.copy(elevatedPrivilegeAcknowledged = true).canStartSetup,
        )
    }

    @Test
    fun canStartSetup_doesNotRequireAckWhenNotElevated() {
        assertTrue(
            baseState(step = ProvisionStep.CONFIRM)
                .copy(
                    elevatedPrivilegeDetected = false,
                    elevatedPrivilegeAcknowledged = false,
                )
                .canStartSetup,
        )
    }

    @Test
    fun canStartSetup_requiresConfirmStep() {
        assertFalse(baseState(step = ProvisionStep.INPUT).canStartSetup)
        assertTrue(baseState(step = ProvisionStep.CONFIRM).canStartSetup)
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
