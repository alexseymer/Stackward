package dev.stackward.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheckModelsTest {

    private fun resultWithSeverities(vararg severities: String) = CheckResult(
        timestamp = "t",
        hostname = "h",
        issues = severities.map { CheckIssue(type = "x", severity = it, message = "m") },
        suggestions = emptyList(),
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
