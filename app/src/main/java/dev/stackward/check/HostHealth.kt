package dev.stackward.check

/** What the dashboard should say about one host, derived from stored check data only. */
enum class HostHealth { NEVER_CHECKED, UNREACHABLE, STALE, CLEAN, ISSUES }

object HostHealthMapper {

    /** A result older than this is shown as stale rather than trusted. */
    const val STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000

    /**
     * [lastFailureAt] is the time of the most recent failed check (unreachable, auth
     * rejected, malformed output). A failure newer than the last success wins: the most
     * important signal is "I can't reach it", and it must not hide behind an old green result.
     */
    fun health(
        lastCheckedAt: Long?,
        lastFailureAt: Long?,
        issueCount: Int,
        now: Long,
    ): HostHealth = when {
        lastFailureAt != null && (lastCheckedAt == null || lastFailureAt >= lastCheckedAt) -> HostHealth.UNREACHABLE
        lastCheckedAt == null -> HostHealth.NEVER_CHECKED
        now - lastCheckedAt > STALE_AFTER_MS -> HostHealth.STALE
        issueCount > 0 -> HostHealth.ISSUES
        else -> HostHealth.CLEAN
    }

    /** "just now", "5 minutes ago", "3 hours ago", "3 days ago". */
    fun ageLabel(timestamp: Long, now: Long): String {
        val minutes = ((now - timestamp).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
            minutes < 60 * 24 -> "${minutes / 60} hour${if (minutes / 60 == 1L) "" else "s"} ago"
            else -> "${minutes / (60 * 24)} day${if (minutes / (60 * 24) == 1L) "" else "s"} ago"
        }
    }
}
