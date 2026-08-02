package dev.stackward.onboarding

import android.content.Context
import dev.stackward.connection.HostKeyPinStore
import dev.stackward.connection.SshConnectionManager
import dev.stackward.connection.SshException
import dev.stackward.crypto.AgentKeyManager

/**
 * Runs the Linux bootstrap script over an admin SSH session.
 */
class BootstrapRunner(
    private val context: Context,
) {

    fun loadLinuxBootstrapScript(): String {
        return context.assets.open(LINUX_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    fun loadProxmoxBootstrapScript(): String {
        return context.assets.open(PROXMOX_ASSET_PATH).bufferedReader().use { it.readText() }
    }

    companion object {
        const val LINUX_ASSET_PATH = "scripts/bootstrap_linux.sh"
        const val PROXMOX_ASSET_PATH = "scripts/bootstrap_proxmox.sh"
    }
}

data class BootstrapResult(
    val profile: ServerProfile,
    val bootstrapOutput: String,
    val verificationOutput: String,
    val publicKey: String,
    val script: String,
    val proxmoxTokenId: String? = null,
    val proxmoxTokenSecret: String? = null,
)
