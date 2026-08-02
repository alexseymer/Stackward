package dev.stackward.logs

import dev.stackward.connection.SshConnectionManager
import dev.stackward.onboarding.HostType
import dev.stackward.onboarding.ServerProfile

/**
 * Unified log reader for Phase 4 MVP.
 * All operations are Tier 1 (read-only, no confirmation).
 */
class LogReader(
    private val ssh: SshConnectionManager,
    private val proxmoxApi: dev.stackward.proxmox.ProxmoxApiClient? = null,
) {

    suspend fun readJournal(
        profile: ServerProfile,
        query: JournalQuery = JournalQuery(),
    ): LogReadResult {
        val priorityFlag = query.priority.journalFlag?.let { "-p ${ShellEscape.singleQuote(it)}" } ?: ""
        val since = ShellEscape.singleQuote(query.since.journalValue)
        val maxLines = query.maxLines.coerceIn(1, 500)
        val command = buildString {
            append("journalctl --since ")
            append(since)
            if (priorityFlag.isNotBlank()) {
                append(' ')
                append(priorityFlag)
            }
            append(" -n $maxLines --no-pager")
        }

        val raw = ssh.execute(profile, command)
        val (content, truncated) = LogTruncate.truncate(raw)
        return LogReadResult(content = content, truncated = truncated, source = LogSource.JOURNAL)
    }

    suspend fun readDockerLogs(
        profile: ServerProfile,
        containerId: String,
        tail: Int = 200,
    ): LogReadResult {
        val safeId = ShellEscape.validateContainerId(containerId)
        val safeTail = tail.coerceIn(1, 500)
        val command = "tail -n $safeTail /var/lib/docker/containers/$safeId/*-json.log 2>/dev/null"
        val raw = ssh.execute(profile, command)
        val (content, truncated) = LogTruncate.truncate(raw)
        return LogReadResult(content = content, truncated = truncated, source = LogSource.DOCKER)
    }

    suspend fun listContainers(profile: ServerProfile): List<DockerContainer> {
        val output = ssh.execute(profile, "ls -1 /var/lib/docker/containers/ 2>/dev/null || true")
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.matches(ShellEscape.CONTAINER_ID_PATTERN) }
            .map { DockerContainer(id = it) }
            .toList()
    }

    suspend fun readDigest(profile: ServerProfile): LogDigest {
        val journal = readJournal(
            profile = profile,
            query = JournalQuery(
                since = JournalSince.ONE_HOUR,
                priority = JournalPriority.ERROR,
                maxLines = 100,
            ),
        )

        val dockerSection = runCatching {
            val containers = listContainers(profile).take(5)
            if (containers.isEmpty()) {
                "No Docker container log directories visible."
            } else {
                buildString {
                    appendLine("Docker containers (${containers.size} visible):")
                    containers.forEach { container ->
                        appendLine("- ${container.shortId}")
                    }
                }
            }
        }.getOrElse { error ->
            "Docker: ${error.message}"
        }

        val proxmoxSection = when (profile.hostType) {
            HostType.PROXMOX -> proxmoxApi?.buildDigest(profile)
                ?: "Proxmox API client not configured."
            else -> ""
        }

        val combined = buildString {
            appendLine("=== systemd journal (errors, last hour) ===")
            appendLine(journal.content.ifBlank { "(no entries)" })
            appendLine()
            appendLine("=== Docker ===")
            appendLine(dockerSection)
            if (proxmoxSection.isNotBlank()) {
                appendLine()
                appendLine("=== Proxmox ===")
                appendLine(proxmoxSection)
            }
        }

        val (content, truncated) = LogTruncate.truncate(combined)
        return LogDigest(
            content = content,
            truncated = truncated,
            generatedAt = System.currentTimeMillis(),
        )
    }
}

data class LogDigest(
    val content: String,
    val truncated: Boolean,
    val generatedAt: Long,
)
