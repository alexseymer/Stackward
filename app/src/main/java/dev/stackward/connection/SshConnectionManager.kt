package dev.stackward.connection

import dev.stackward.onboarding.ServerProfile

/**
 * SSH connection manager with jump-host and port-forward support.
 *
 * Phase 1 responsibilities:
 * - Direct SSH via SSHJ
 * - Jump-host tunneling (ProxyJump equivalent via channel forwarding)
 * - Local port-forward for Proxmox API (:8006) and internal Docker hosts
 * - Host key pinning (TOFU) per hop
 * - Reconnect-with-backoff
 */
class SshConnectionManager {

    /**
     * Execute a command on the target host and return stdout.
     */
    suspend fun execute(
        profile: ServerProfile,
        command: String,
    ): String {
        // TODO: Phase 1 — SSHJ connect + exec
        // If jumpHost is set, open channel through jump host first
        TODO("Phase 1: SSH command execution")
    }

    /**
     * Open a local port-forward through the SSH tunnel.
     * Used for reaching Proxmox API or internal hosts on the LAN.
     */
    suspend fun portForward(
        profile: ServerProfile,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ) {
        // TODO: Phase 1 — SSHJ local port forward
        TODO("Phase 1: local port forward for Proxmox API / internal hosts")
    }

    /**
     * Verify host key fingerprint matches the pinned value.
     * Alert if changed (possible MITM).
     */
    fun verifyHostKey(profile: ServerProfile, actualFingerprint: String): Boolean {
        return profile.hostKeyFingerprint == actualFingerprint
    }
}
