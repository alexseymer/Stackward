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

    suspend fun readDockerContainerContext(
        profile: ServerProfile,
        containerId: String,
        tail: Int = 200,
    ): LogReadResult {
        val safeId = ShellEscape.validateContainerId(containerId)
        val inspectCommand = buildString {
            append("if command -v docker >/dev/null 2>&1; then ")
            append("docker inspect --format ")
            append("'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' ")
            append(safeId)
            append(" 2>/dev/null; else echo inspect_unavailable; fi")
        }
        val inspect = runCatching { ssh.execute(profile, inspectCommand).trim() }
            .getOrDefault("inspect_unavailable")
        val logs = readDockerLogs(profile, containerId, tail)
        val combined = buildString {
            appendLine("=== container inspect ===")
            appendLine(inspect)
            appendLine()
            appendLine("=== recent logs ===")
            append(logs.content)
        }
        val (content, truncated) = LogTruncate.truncate(combined)
        return LogReadResult(
            content = content,
            truncated = truncated || logs.truncated,
            source = LogSource.DOCKER,
        )
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
            val containers = listContainers(profile).take(3)
            if (containers.isEmpty()) {
                "No Docker container log directories visible."
            } else {
                buildString {
                    appendLine("Docker containers (${containers.size} sampled):")
                    containers.forEach { container ->
                        appendLine("--- ${container.shortId} ---")
                        val tail = readDockerLogs(profile, container.id, tail = 30)
                        appendLine(tail.content.ifBlank { "(no log lines)" })
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

        val anomalyFlags = DigestAnomalyDetector.detect(
            journalContent = journal.content,
            dockerSection = dockerSection,
            proxmoxSection = proxmoxSection,
        )

        val combined = buildString {
            if (anomalyFlags.isNotEmpty()) {
                appendLine("=== Anomaly flags ===")
                anomalyFlags.forEach { flag ->
                    appendLine("- ${DigestAnomalyDetector.label(flag)}")
                }
                appendLine()
            }
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
            anomalyFlags = anomalyFlags,
        )
    }
}

data class LogDigest(
    val content: String,
    val truncated: Boolean,
    val generatedAt: Long,
    val anomalyFlags: List<String> = emptyList(),
)
