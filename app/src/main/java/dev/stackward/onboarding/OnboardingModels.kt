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
