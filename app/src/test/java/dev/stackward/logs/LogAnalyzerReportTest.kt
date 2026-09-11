package dev.stackward.logs

import dev.stackward.inference.SummarizationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class LogAnalyzerReportTest {

    @Test
    fun buildMarkdown_includesHeuristicsAndSummary() {
        val flags = LogHeuristicAnalyzer.analyze(SampleLogFixture.NGINX_502.content)
        val summary = SummarizationResult(
            summary = "nginx cannot reach upstream on port 8080",
            proposals = emptyList(),
            usedOnDeviceModel = true,
        )

        val report = LogAnalyzerReport.buildMarkdown(
            sourceLabel = "Sample",
            logs = SampleLogFixture.NGINX_502.content,
            heuristicFlags = flags,
            summarization = summary,
            userQuestion = "Why 502?",
        )

        assertTrue(report.contains("# Stackward log analysis"))
        assertTrue(report.contains("## Heuristic flags"))
        assertTrue(report.contains("## On-device summary (Gemma)"))
        assertTrue(report.contains("nginx cannot reach upstream"))
        assertTrue(report.contains("**Question:** Why 502?"))
        assertTrue(report.contains("## Raw logs"))
    }
}
