package dev.stackward.logs

/**
 * Fast, offline heuristics over arbitrary log text (no ML).
 */
object LogHeuristicAnalyzer {

    data class Flag(
        val id: String,
        val label: String,
        val count: Int,
        val sampleLine: String? = null,
    )

    fun analyze(logs: String): List<Flag> {
        if (logs.isBlank()) return emptyList()

        val lines = logs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()

        val flags = mutableListOf<Flag>()

        countMatches(lines, id = "errors", label = "Error lines", pattern = Regex("\\berror\\b", RegexOption.IGNORE_CASE))
            ?.let(flags::add)
        countMatches(lines, id = "fatals", label = "Fatal / critical lines", pattern = Regex("\\b(fatal|critical|panic)\\b", RegexOption.IGNORE_CASE))
            ?.let(flags::add)
        countMatches(lines, id = "exceptions", label = "Exceptions / stack traces", pattern = Regex("(Exception|Traceback|panic:)", RegexOption.IGNORE_CASE))
            ?.let(flags::add)
        countMatches(lines, id = "http_5xx", label = "HTTP 5xx responses", pattern = Regex("\\b5\\d{2}\\b"))
            ?.let(flags::add)
        countMatches(
            lines,
            id = "connection_failures",
            label = "Connection failures",
            pattern = Regex("(connection refused|ECONNREFUSED|connection timed out|ETIMEDOUT|no route to host)", RegexOption.IGNORE_CASE),
        )?.let(flags::add)
        countMatches(lines, id = "oom", label = "Out-of-memory signals", pattern = Regex("(out of memory|OOM|Killed process)", RegexOption.IGNORE_CASE))
            ?.let(flags::add)
        countMatches(lines, id = "restarts", label = "Restart / crash loops", pattern = Regex("(restart(ing)?|crash loop|exited with code)", RegexOption.IGNORE_CASE))
            ?.let(flags::add)

        return flags
    }

    private fun countMatches(lines: List<String>, id: String, label: String, pattern: Regex): Flag? {
        val matches = lines.filter { pattern.containsMatchIn(it) }
        if (matches.isEmpty()) return null
        return Flag(
            id = id,
            label = label,
            count = matches.size,
            sampleLine = matches.first().take(160),
        )
    }
}
