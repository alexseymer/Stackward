package dev.stackward.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class PortKnockerTest {

    @Test
    fun parseSequence_splitsCommaAndSpaceSeparatedPorts() {
        assertEquals(listOf(7000, 8000, 9000), PortKnocker.parseSequence("7000,8000,9000"))
        assertEquals(listOf(1234, 5678), PortKnocker.parseSequence("1234 5678"))
    }

    @Test
    fun parseSequence_ignoresInvalidTokens() {
        assertEquals(listOf(22), PortKnocker.parseSequence("22,abc,70000"))
    }

    @Test
    fun parseSequence_blankReturnsEmpty() {
        assertEquals(emptyList<Int>(), PortKnocker.parseSequence(""))
    }
}
