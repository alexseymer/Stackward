package dev.stackward.logs

import dev.stackward.onboarding.ServerProfile
import dev.stackward.connection.SshConnectionManager

/**
 * Unified log reader for Phase 4 MVP.
 * All operations are Tier 1 (read-only, no confirmation).
 */
class LogReader(
    private val ssh: SshConnectionManager,
) {

    /**
     * Read systemd journal entries, pre-filtered for model context.
     */
    suspend fun readJournal(
        profile: ServerProfile,
        since: String = "1 hour ago",
        priority: String = "err",
        maxLines: Int = 200,
    ): String {
        val command = "journalctl --since \"$since\" -p $priority -n $maxLines --no-pager"
        return ssh.execute(profile, command)
    }

    /**
     * Read Docker container logs via file ACL (not docker group).
     */
    suspend fun readDockerLogs(
        profile: ServerProfile,
        containerId: String,
        tail: Int = 200,
    ): String {
        val command = "tail -n $tail /var/lib/docker/containers/$containerId/*-json.log 2>/dev/null"
        return ssh.execute(profile, command)
    }

    /**
     * List running Docker containers (read-only).
     */
    suspend fun listContainers(profile: ServerProfile): String {
        // Uses docker CLI if available, or parses container log directory
        val command = "ls -1 /var/lib/docker/containers/ 2>/dev/null"
        return ssh.execute(profile, command)
    }

    /**
     * Read Proxmox VM/LXC status via scoped API token.
     */
    suspend fun readProxmoxStatus(
        profile: ServerProfile,
        // proxmoxClient: ProxmoxApiClient,
    ): String {
        // TODO: Phase 4 — Proxmox REST API via port-forwarded :8006
        TODO("Phase 4: Proxmox API status read")
    }
}
