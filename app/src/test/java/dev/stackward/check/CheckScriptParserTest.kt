package dev.stackward.check

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckScriptParserTest {

    private val validJson = """
        {
          "timestamp": "2026-09-25T20:22:02Z",
          "hostname": "vm",
          "issues": [
            { "type": "disk", "severity": "high", "message": "/opt at 91%" }
          ],
          "suggestions": [
            { "id": "review-logs", "risk": "safe", "action": "tail_journal", "reason": "Inspect recent journal errors" }
          ]
        }
    """.trimIndent()

    @Test
    fun parse_readsIssuesAndSuggestions() {
        val result = CheckScriptParser.parse(validJson)

        assertEquals("vm", result.hostname)
        assertEquals(1, result.issues.size)
        assertEquals("disk", result.issues[0].type)
        assertEquals("high", result.issues[0].severity)
        assertEquals(1, result.suggestions.size)
        assertEquals("tail_journal", result.suggestions[0].action)
    }

    @Test
    fun parse_handlesEmptyArrays() {
        val json = """{"timestamp":"t","hostname":"h","issues":[],"suggestions":[]}"""
        val result = CheckScriptParser.parse(json)

        assertEquals(0, result.issues.size)
        assertEquals(0, result.suggestions.size)
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
        val json = """{"timestamp":"t","issues":[],"suggestions":[]}""" // hostname missing
        assertThrows(CheckScriptParseException::class.java) { CheckScriptParser.parse(json) }
    }

    @Test
    fun toJsonString_roundTripsThroughParse() {
        val original = CheckScriptParser.parse(validJson)
        val roundTripped = CheckScriptParser.parse(CheckScriptParser.toJsonString(original))

        assertEquals(original, roundTripped)
    }
}
