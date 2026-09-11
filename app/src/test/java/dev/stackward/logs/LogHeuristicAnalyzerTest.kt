package dev.stackward.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogHeuristicAnalyzerTest {

    @Test
    fun analyze_detectsNginx502Sample() {
        val flags = LogHeuristicAnalyzer.analyze(SampleLogFixture.NGINX_502.content)
        assertTrue(flags.any { it.id == "errors" })
        assertTrue(flags.any { it.id == "http_5xx" })
        assertTrue(flags.any { it.id == "connection_failures" })
    }

    @Test
    fun analyze_detectsDockerOomSample() {
        val flags = LogHeuristicAnalyzer.analyze(SampleLogFixture.DOCKER_OOM.content)
        assertTrue(flags.any { it.id == "oom" })
        assertTrue(flags.any { it.id == "restarts" })
    }

    @Test
    fun analyze_returnsEmptyForBlankInput() {
        assertTrue(LogHeuristicAnalyzer.analyze("").isEmpty())
        assertTrue(LogHeuristicAnalyzer.analyze("   \n  ").isEmpty())
    }

    @Test
    fun analyze_includesSampleLine() {
        val flag = LogHeuristicAnalyzer.analyze(SampleLogFixture.POSTGRES_DISK.content)
            .first { it.id == "fatals" }
        assertTrue(flag.count >= 1)
        assertTrue(
            flag.sampleLine?.contains("FATAL", ignoreCase = true) == true ||
                flag.sampleLine?.contains("PANIC", ignoreCase = true) == true,
        )
    }
}
