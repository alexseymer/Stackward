package dev.stackward.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogUtilsTest {

    @Test
    fun truncate_leavesShortTextUntouched() {
        val (text, truncated) = LogTruncate.truncate("hello")

        assertEquals("hello", text)
        assertFalse(truncated)
    }

    @Test
    fun truncate_addsMarkerWhenOverLimit() {
        val input = "x".repeat(100)
        val (text, truncated) = LogTruncate.truncate(input, maxChars = 50)

        assertTrue(truncated)
        assertTrue(text.length <= 50)
        assertTrue(text.contains("truncated"))
    }

    @Test
    fun singleQuote_escapesEmbeddedQuotes() {
        assertEquals("'it'\"'\"'s fine'", ShellEscape.singleQuote("it's fine"))
    }

    @Test
    fun validateContainerId_acceptsHexIds() {
        assertEquals("abc123def456", ShellEscape.validateContainerId("abc123def456"))
    }
}
