package dev.stackward.logs

import dev.stackward.inference.SummarizationResult

/**
 * Combines heuristic flags with optional on-device SLM output for sharing/export.
 */
object LogAnalyzerReport {

    fun buildMarkdown(
        sourceLabel: String,
        logs: String,
        heuristicFlags: List<LogHeuristicAnalyzer.Flag>,
        summarization: SummarizationResult? = null,
        userQuestion: String? = null,
    ): String {
        val sections = mutableListOf<String>()
        sections += "# Stackward log analysis"
        sections += ""
        sections += "**Source:** $sourceLabel"
        userQuestion?.trim()?.takeIf { it.isNotEmpty() }?.let { question ->
            sections += "**Question:** $question"
        }
        sections += "**Log size:** ${logs.length} characters, ${logs.lines().size} lines"
        sections += ""

        if (heuristicFlags.isNotEmpty()) {
            sections += "## Heuristic flags"
            heuristicFlags.forEach { flag ->
                val sample = flag.sampleLine?.let { " — e.g. `$it`" } ?: ""
                sections += "- ${flag.label}: ${flag.count}$sample"
            }
            sections += ""
        } else {
            sections += "## Heuristic flags"
            sections += "- No obvious error patterns detected."
            sections += ""
        }

        when {
            summarization == null -> Unit
            summarization.usedOnDeviceModel -> {
                sections += "## On-device summary (Gemma)"
                sections += summarization.summary.trim()
                sections += ""
                if (summarization.proposals.isNotEmpty()) {
                    sections += "## Proposed follow-ups"
                    summarization.proposals.forEach { proposal ->
                        sections += "- [${proposal.tier.name}] ${proposal.action}: `${proposal.command}`"
                        sections += "  - ${proposal.reason}"
                    }
                    sections += ""
                }
            }
            else -> {
                sections += "## On-device summary"
                sections += summarization.unavailableReason ?: "Unavailable"
                sections += ""
            }
        }

        sections += "## Raw logs"
        sections += "```"
        sections += logs.trimEnd()
        sections += "```"

        return sections.joinToString("\n")
    }
}
