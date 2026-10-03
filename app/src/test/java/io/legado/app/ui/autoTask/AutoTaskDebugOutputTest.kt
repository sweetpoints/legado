package io.legado.app.ui.autoTask

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoTaskDebugOutputTest {
    @Test
    fun joiningLogLinesDoesNotAddALeadingNewline() {
        assertEquals("first\nsecond", appendDebugOutput(appendDebugOutput("", "first"), "second"))
    }

    @Test
    fun oversizedOutputRetainsTheLatestResult() {
        val output = appendDebugOutput("old log".repeat(4_000), "latest result")
        assertEquals(20_000, output.length)
        assertEquals("latest result", output.takeLast(13))
    }

    @Test
    fun singleOversizedMessageIsAlsoBounded() {
        assertEquals("ending", appendDebugOutput("", "x".repeat(30_000) + "ending", maxLength = 6))
    }
}
