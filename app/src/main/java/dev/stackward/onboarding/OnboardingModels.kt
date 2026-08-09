package dev.stackward.onboarding

/**
 * Host type detected or declared during onboarding.
 */
enum class HostType {
    PLAIN_LINUX,
    PROXMOX,
    DOCKER,
}

/**
 * How the one-time setup SSH session authenticates to the chosen SSH user.
 * Credentials are held in memory only for the session — never persisted.
 *
 * Password auth is the normal path (ssh-copy-id style). Private-key / agent-key
 * auth is only for hosts that already have this device's public key installed.
 */
enum class BootstrapAuthMethod {
    PASSWORD,
    PRIVATE_KEY,
}

/**
 * Server connection metadata stored locally after provisioning.
 * Never includes passwords or one-time bootstrap private keys.
 */
data class ServerProfile(
    val id: String,
    val host: String,
    val port: Int = 22,
    /** SSH user for this profile. Default recommendation: stackward-agent. */
    val username: String = DEFAULT_AGENT_USERNAME,
    val hostType: HostType,
    val hostKeyFingerprint: String,
    val jumpHost: String? = null,
    val jumpHostPort: Int = 22,
    val jumpHostKeyFingerprint: String? = null,
    val proxmoxPort: Int = 8006,
    val provisionedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_AGENT_USERNAME = "stackward-agent"
    }
}

/**
 * One-time login to the chosen SSH user. Never persisted.
 *
 * For [BootstrapAuthMethod.PRIVATE_KEY], either a pasted PEM or the on-device
 * agent key ([useAgentKey]) may authenticate — never both required.
 */
data class BootstrapLogin(
    val method: BootstrapAuthMethod,
    val password: String? = null,
    val privateKeyPem: String? = null,
    val privateKeyPassphrase: String? = null,
    val useAgentKey: Boolean = false,
) {
    init {
        when (method) {
            BootstrapAuthMethod.PASSWORD ->
                require(!password.isNullOrBlank()) { "SSH password required" }
            BootstrapAuthMethod.PRIVATE_KEY ->
                require(useAgentKey || !privateKeyPem.isNullOrBlank()) {
                    "SSH private key or on-device agent key required"
                }
        }
    }
}
