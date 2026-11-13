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

    /** Coarse age for display; the UI turns it into a localized, pluralized string. */
    fun age(timestamp: Long, now: Long): Age {
        val minutes = ((now - timestamp).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> Age(AgeUnit.NOW, 0)
            minutes < 60 -> Age(AgeUnit.MINUTES, minutes.toInt())
            minutes < 60 * 24 -> Age(AgeUnit.HOURS, (minutes / 60).toInt())
            else -> Age(AgeUnit.DAYS, (minutes / (60 * 24)).toInt())
        }
    }

    /** English label, kept for tests and logs: "just now", "5 minutes ago", "3 days ago". */
    fun ageLabel(timestamp: Long, now: Long): String {
        val age = age(timestamp, now)
        fun plural(unit: String) = "${age.value} $unit${if (age.value == 1) "" else "s"} ago"
        return when (age.unit) {
            AgeUnit.NOW -> "just now"
            AgeUnit.MINUTES -> plural("minute")
            AgeUnit.HOURS -> plural("hour")
            AgeUnit.DAYS -> plural("day")
        }
    }
}

enum class AgeUnit { NOW, MINUTES, HOURS, DAYS }

data class Age(val unit: AgeUnit, val value: Int)
