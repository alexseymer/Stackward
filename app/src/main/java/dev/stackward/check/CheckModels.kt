package dev.stackward.check

/** One detected problem, as reported by `~/.stackward/check.sh`. See PRD.md §5.0. */
data class CheckIssue(
    val type: String,
    val severity: String,
    val message: String,
)

/**
 * One proposed improvement, as reported by check.sh. [risk] is the host's own
 * classification — never trusted for gating. See [dev.stackward.check.CheckSuggestionGate].
 */
data class CheckSuggestion(
    val id: String,
    val risk: String,
    val action: String,
    val reason: String,
)

/** Parsed result of one `check.sh` run against a host. */
data class CheckResult(
    val timestamp: String,
    val hostname: String,
    val issues: List<CheckIssue>,
    val suggestions: List<CheckSuggestion>,
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
