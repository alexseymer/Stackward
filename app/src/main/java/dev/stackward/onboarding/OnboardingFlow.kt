package dev.stackward.onboarding

import dev.stackward.crypto.AgentKeyManager

/**
 * Host type detected or declared during onboarding.
 */
enum class HostType {
    PLAIN_LINUX,
    PROXMOX,
    DOCKER,
}

/**
 * Server connection metadata stored locally after provisioning.
 */
data class ServerProfile(
    val id: String,
    val host: String,
    val port: Int = 22,
    val hostType: HostType,
    val hostKeyFingerprint: String,
    val jumpHost: String? = null,
    val proxmoxPort: Int = 8006,
    val provisionedAt: Long = System.currentTimeMillis(),
)

/**
 * One-time admin credential used only during bootstrap. Never persisted.
 */
data class AdminCredential(
    val type: CredentialType,
    val value: String,
)

enum class CredentialType {
    SSH_PASSWORD,
    SSH_PRIVATE_KEY,
}

/**
 * Onboarding flow: IP/port → review bootstrap script → provision → verify.
 *
 * Phase 0/1 responsibilities:
 * 1. Collect IP/hostname, port, and one-time admin credential
 * 2. Show bootstrap script to user for review
 * 3. Generate SSH keypair via [AgentKeyManager]
 * 4. Run bootstrap_linux.sh (and bootstrap_proxmox.sh if Proxmox)
 * 5. Verify connection with new restricted key
 * 6. Discard admin credential (never store)
 * 7. Pin host key (TOFU)
 */
class OnboardingFlow(
    private val keyManager: AgentKeyManager = AgentKeyManager(),
) {

    /**
     * Start the onboarding flow. Returns the provisioned [ServerProfile] on success.
     */
    suspend fun start(
        host: String,
        port: Int,
        adminCredential: AdminCredential,
        hostType: HostType? = null,
    ): ServerProfile {
        // TODO: Phase 0/1
        // 1. Detect host type if not provided (probe pveversion, docker, etc.)
        // 2. Generate keypair
        // 3. Show bootstrap script to user
        // 4. Connect with admin credential, run bootstrap script with public key
        // 5. If Proxmox: run bootstrap_proxmox.sh, store API token
        // 6. Verify connection with new Keystore key
        // 7. Pin host key fingerprint
        // 8. Discard admin credential
        TODO("Phase 0/1: implement full onboarding flow")
    }

    /**
     * Auto-detect host type by probing common indicators over SSH.
     */
    suspend fun detectHostType(
        host: String,
        port: Int,
        adminCredential: AdminCredential,
    ): HostType {
        // TODO: run `command -v pveversion` and `command -v docker` over SSH
        TODO("Phase 0: auto-detect Proxmox / Docker / plain Linux")
    }

    /**
     * Return the bootstrap script content for user review before execution.
     */
    fun getBootstrapScript(hostType: HostType, publicKey: String): String {
        return when (hostType) {
            HostType.PROXMOX -> PROXMOX_BOOTSTRAP_TEMPLATE
            else -> LINUX_BOOTSTRAP_TEMPLATE.replace("{{PUBLIC_KEY}}", publicKey)
        }
    }

    companion object {
        private const val LINUX_BOOTSTRAP_TEMPLATE = """
            # scripts/bootstrap_linux.sh
            # Creates gemma-agent user, restricted key, journal + Docker log ACLs.
            # Public key: {{PUBLIC_KEY}}
        """

        private const val PROXMOX_BOOTSTRAP_TEMPLATE = """
            # scripts/bootstrap_linux.sh + scripts/bootstrap_proxmox.sh
            # Creates gemma-agent user + scoped Proxmox API token.
        """
    }
}
