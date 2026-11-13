package dev.stackward.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckScriptParserTest {

    private val validJson = """
        {
          "timestamp": "2026-09-25T20:22:02Z",
          "hostname": "vm",
          "issues": [
            { "type": "disk", "severity": "high", "message": "/opt at 91%", "advice": "Find large files with: du -xh / | sort -h | tail" }
          ],
          "coverage": { "journal": "denied", "docker": "absent", "systemd": "ok" }
        }
    """.trimIndent()

    @Test
    fun parse_readsIssuesAdviceAndCoverage() {
        val result = CheckScriptParser.parse(validJson)

        assertEquals("vm", result.hostname)
        assertEquals(1, result.issues.size)
        assertEquals("high", result.issues[0].severity)
        assertEquals("Find large files with: du -xh / | sort -h | tail", result.issues[0].advice)
        assertEquals(CheckCoverage("denied", "absent", "ok"), result.coverage)
    }

    @Test
    fun parse_missingAdviceAndCoverageAreTolerated() {
        val json = """{"timestamp":"t","hostname":"h","issues":[{"type":"x","severity":"low","message":"m"}]}"""
        val result = CheckScriptParser.parse(json)

        assertNull(result.issues[0].advice)
        assertEquals(CheckCoverage(), result.coverage)
    }

    @Test
    fun parse_ignoresLegacySuggestions() {
        val json = """{"timestamp":"t","hostname":"h","issues":[],"suggestions":[{"id":"a","risk":"safe","action":"rm -rf /"}]}"""
        val result = CheckScriptParser.parse(json)

        assertEquals(0, result.issues.size)
        assertEquals(CheckResult("t", "h", emptyList(), CheckCoverage()), result)
    }

    @Test
    fun parse_rejectsEmptyOutput() {
        assertThrows(CheckScriptParseException::class.java) { CheckScriptParser.parse("") }
    }

    @Test
    fun parse_rejectsInvalidJson() {
        assertThrows(CheckScriptParseException::class.java) { CheckScriptParser.parse("not json") }
    }

    @Test
    fun parse_rejectsMissingRequiredField() {
        val json = """{"timestamp":"t","issues":[]}""" // hostname missing
        assertThrows(CheckScriptParseException::class.java) { CheckScriptParser.parse(json) }
    }

    @Test
    fun toJsonString_roundTripsThroughParse() {
        val original = CheckScriptParser.parse(validJson)
        val roundTripped = CheckScriptParser.parse(CheckScriptParser.toJsonString(original))

        assertEquals(original, roundTripped)
    }
}
