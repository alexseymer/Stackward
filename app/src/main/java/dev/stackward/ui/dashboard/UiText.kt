package dev.stackward.ui.dashboard

import android.content.Context
import dev.stackward.R
import dev.stackward.check.Age
import dev.stackward.check.AgeUnit
import dev.stackward.check.CoverageGap
import dev.stackward.check.HostHealth
import dev.stackward.check.HostHealthMapper

/** Localized text for dashboard/host-detail state. Strings live in the res values folders (strings.xml, en and de). */
object UiText {

    fun age(context: Context, age: Age): String = when (age.unit) {
        AgeUnit.NOW -> context.getString(R.string.age_just_now)
        AgeUnit.MINUTES -> context.resources.getQuantityString(R.plurals.age_minutes, age.value, age.value)
        AgeUnit.HOURS -> context.resources.getQuantityString(R.plurals.age_hours, age.value, age.value)
        AgeUnit.DAYS -> context.resources.getQuantityString(R.plurals.age_days, age.value, age.value)
    }

    fun healthName(context: Context, health: HostHealth): String = context.getString(
        when (health) {
            HostHealth.NEVER_CHECKED -> R.string.health_never_checked
            HostHealth.UNREACHABLE -> R.string.health_unreachable
            HostHealth.STALE -> R.string.health_stale
            HostHealth.CLEAN -> R.string.health_clean
            HostHealth.ISSUES -> R.string.health_issues
        },
    )

    fun gap(context: Context, gap: CoverageGap): String = context.getString(
        if (gap.denied) R.string.gap_denied else R.string.gap_unknown,
        gap.source,
    )

    fun statusLabel(context: Context, host: HostSummary, now: Long = System.currentTimeMillis()): String {
        val res = context.resources
        fun ageOf(ts: Long) = age(context, HostHealthMapper.age(ts, now))
        val checked = host.lastCheckedAt?.let(::ageOf)
        val issues = res.getQuantityString(R.plurals.issue_count, host.issueCount, host.issueCount)
        val blind = if (host.blindSpots > 0) {
            " · " + res.getQuantityString(R.plurals.blind_spot_count, host.blindSpots, host.blindSpots)
        } else {
            ""
        }
        return when (host.health) {
            HostHealth.NEVER_CHECKED -> context.getString(R.string.status_never_checked)
            HostHealth.UNREACHABLE -> {
                val failedAgo = host.lastFailureAt?.let(::ageOf) ?: context.getString(R.string.age_just_now)
                val last = checked?.let { context.getString(R.string.status_last_good, it) } ?: ""
                context.getString(R.string.status_unreachable, failedAgo) + last
            }
            HostHealth.STALE -> {
                val extra = if (host.issueCount > 0) " · $issues" else ""
                context.getString(R.string.status_stale, checked ?: "") + extra + blind
            }
            HostHealth.CLEAN ->
                if (host.blindSpots > 0) {
                    context.getString(R.string.status_clean_blind, blind, checked ?: "")
                } else {
                    context.getString(R.string.status_clean, checked ?: "")
                }
            HostHealth.ISSUES -> context.getString(R.string.status_issues, issues, blind, checked ?: "")
        }
    }

    fun batchMessage(context: Context, total: Int, failed: Int): String =
        if (failed == 0) {
            context.resources.getQuantityString(R.plurals.batch_all_ok, total, total)
        } else {
            context.resources.getQuantityString(R.plurals.batch_some_failed, total, total, failed)
        }
}
