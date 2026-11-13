package dev.stackward.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckModelsTest {

    private fun resultWithSeverities(vararg severities: String) = CheckResult(
        timestamp = "t",
        hostname = "h",
        issues = severities.map { CheckIssue(type = "x", severity = it, message = "m") },
    )

    @Test
    fun maxSeverity_nullWhenNoIssues() {
        assertNull(resultWithSeverities().maxSeverity())
    }

    @Test
    fun maxSeverity_picksHighestAmongMixed() {
        assertEquals(IssueSeverity.CRITICAL, resultWithSeverities("low", "high", "critical").maxSeverity())
    }

    @Test
    fun maxSeverity_unknownStringTreatedAsLow() {
        assertEquals(IssueSeverity.LOW, resultWithSeverities("weird-value").maxSeverity())
    }
}

class CoverageGapsTest {
    private fun result(coverage: CheckCoverage) = CheckResult("t", "h", emptyList(), coverage)

    @Test
    fun coverageGaps_emptyWhenEverythingReadableOrAbsent() {
        assertEquals(emptyList<String>(), result(CheckCoverage("ok", "absent", "ok")).coverageGaps())
    }

    @Test
    fun coverageGaps_namesDeniedSources() {
        assertEquals(listOf("Journal not readable", "Docker not readable"), result(CheckCoverage("denied", "denied", "ok")).coverageGaps())
    }

    @Test
    fun coverageGaps_oldCheckScriptIsNeverAnAllClear() {
        assertEquals(3, result(CheckCoverage()).coverageGaps().size)
    }
}
