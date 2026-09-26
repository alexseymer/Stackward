package dev.stackward.logs

/**
 * Heuristic anomaly flags for scheduled digests (v1 — no ML required).
 */
object DigestAnomalyDetector {

    fun detect(
        journalContent: String,
        dockerSection: String,
        proxmoxSection: String,
    ): List<String> {
        val flags = mutableListOf<String>()
        val journal = journalContent.trim()
        if (journal.isNotEmpty() && journal != "(no entries)") {
            flags.add("journal_errors")
        }
        if (dockerSection.contains("error", ignoreCase = true) ||
            dockerSection.contains("fatal", ignoreCase = true) ||
            dockerSection.contains("panic", ignoreCase = true)
        ) {
            flags.add("docker_errors")
        }
        if (dockerSection.contains("Restarting", ignoreCase = true) ||
            dockerSection.contains("restart loop", ignoreCase = true)
        ) {
            flags.add("docker_restart")
        }
        if (proxmoxSection.contains("failed", ignoreCase = true) ||
            proxmoxSection.contains("error", ignoreCase = true) ||
            proxmoxSection.contains("TASK ERROR", ignoreCase = true)
        ) {
            flags.add("proxmox_task_failure")
        }
        return flags
    }

    fun label(flag: String): String = when (flag) {
        "journal_errors" -> "Journal errors (last hour)"
        "docker_errors" -> "Docker log errors"
        "docker_restart" -> "Docker restart activity"
        "proxmox_task_failure" -> "Proxmox task failure"
        else -> flag
    }
}
