package dev.stackward.logs

/**
 * Query parameters for journal reads. Values are validated before shell use.
 */
data class JournalQuery(
    val since: JournalSince = JournalSince.ONE_HOUR,
    val priority: JournalPriority = JournalPriority.ERROR,
    val maxLines: Int = 200,
)

enum class JournalSince(val journalValue: String) {
    ONE_HOUR("1 hour ago"),
    SIX_HOURS("6 hours ago"),
    TWENTY_FOUR_HOURS("24 hours ago"),
}

enum class JournalPriority(val journalFlag: String?) {
    ERROR("err"),
    WARNING("warning"),
    ALL(null),
}

data class LogReadResult(
    val content: String,
    val truncated: Boolean,
    val source: LogSource,
)

enum class LogSource {
    JOURNAL,
    DOCKER,
    PROXMOX,
}

data class DockerContainer(
    val id: String,
    val shortId: String = id.take(12),
)
