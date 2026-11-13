package dev.stackward.check

/**
 * One detected problem, as reported by `~/.stackward/check.sh`. [advice] is text for a
 * human to read (and maybe run by hand) — the app never executes it. See PRD.md §5.1.
 */
data class CheckIssue(
    val type: String,
    val severity: String,
    val message: String,
    val advice: String? = null,
)

/** Whether a data source could be read: `ok`, `denied`, `absent` (not installed) or `unknown` (old check.sh). */
data class CheckCoverage(
    val journal: String = UNKNOWN,
    val docker: String = UNKNOWN,
    val systemd: String = UNKNOWN,
) {
    companion object {
        const val UNKNOWN = "unknown"
    }
}

/** Parsed result of one `check.sh` run against a host. */
data class CheckResult(
    val timestamp: String,
    val hostname: String,
    val issues: List<CheckIssue>,
    val coverage: CheckCoverage = CheckCoverage(),
)

/** Ordinal order is significant: later entries outrank earlier ones. */
enum class IssueSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    companion object {
        fun fromString(value: String): IssueSeverity = when (value.lowercase()) {
            "critical" -> CRITICAL
            "high" -> HIGH
            "medium" -> MEDIUM
            else -> LOW
        }
    }
}

/** Highest severity across all issues, or null if there are none (host is clean). */
fun CheckResult.maxSeverity(): IssueSeverity? =
    issues.map { IssueSeverity.fromString(it.severity) }.maxOrNull()

/**
 * Human-readable blind spots: sources check.sh could not read (or, for an old copy,
 * could not say anything about). An empty list plus no issues is the only real "all clear".
 */
fun CheckResult.coverageGaps(): List<String> = buildList {
    fun gap(name: String, value: String) {
        when (value) {
            "denied" -> add("$name not readable")
            CheckCoverage.UNKNOWN -> add("$name coverage unknown (old check.sh)")
        }
    }
    gap("Journal", coverage.journal)
    gap("Docker", coverage.docker)
    gap("systemd", coverage.systemd)
}
