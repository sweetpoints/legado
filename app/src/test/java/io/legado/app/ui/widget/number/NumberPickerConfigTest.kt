package io.legado.app.ui.widget.number

import org.junit.Assert.*
import org.junit.Test

class NumberPickerConfigTest {
    @Test
    fun numericInputStaysInRangeAndEmptyInputRetainsCurrentValue() {
        val config = NumberPickerConfig(minimum = 9, maximum = 36)
        assertEquals(20, config.fromText("20", 12))
        assertEquals(9, config.fromText("1", 12))
        assertEquals(36, config.fromText("999", 12))
        assertEquals(12, config.fromText("", 12))
        assertEquals(9, config.fromText("invalid", 12))
    }

    @Test
    fun decimalLabelsKeepTheirRawIntegerCallbackValues() {
        val config = NumberPickerConfig(minimum = 5, maximum = 30, decimal = true)
        assertEquals("0.5", config.label(5))
        assertEquals("1.2", config.label(12))
        assertEquals(12, config.fromText("1.2", 5))
        assertEquals(10, config.fromText("1.", 5))
    }

    @Test
    fun customLabelsOverrideDecimalFormattingAndAllowCaseInsensitivePrefixes() {
        val config =
            NumberPickerConfig(
                minimum = 1,
                maximum = 3,
                decimal = true,
                labels = listOf("First", "Second", "Third"),
            )
        assertEquals("Second", config.label(2))
        assertEquals(2, config.fromText("sec", 1))
        assertEquals(3, config.fromText("THIRD", 1))
    }

    @Test
    fun percentageLabelsReturnTheirIndexWithoutParsingTheDisplayAsARawNumber() {
        val config =
            NumberPickerConfig(minimum = 0, maximum = 150, labels = List(151) { "${it - 50}%" })
        assertEquals(20, config.fromText("-30%", 50))
        assertEquals(150, config.fromText("100%", 50))
        assertEquals("0%", config.label(50))
    }

    @Test
    fun wheelWrapsAtBothEndsForTheNativeDefaultFourOrMoreValues() {
        val config = NumberPickerConfig(minimum = 10, maximum = 13)
        assertTrue(config.wraps)
        assertEquals(10, config.step(13, 1))
        assertEquals(13, config.step(10, -1))
        assertEquals(10, config.valueAt(0))
        assertEquals(13, config.valueAt(3))
        assertEquals(10, config.valueAt(4))
    }

    @Test
    fun shortWheelStopsAtItsBounds() {
        val config = NumberPickerConfig(minimum = 1, maximum = 3)
        assertFalse(config.wraps)
        assertEquals(1, config.step(1, -1))
        assertEquals(3, config.step(3, 1))
        assertEquals(3, config.wheelCount)
    }

    @Test
    fun virtualWheelRestoresTheSameValueAndUsesTheClosestCycle() {
        val config = NumberPickerConfig(minimum = 0, maximum = 9999)
        val index = config.wheelIndex(8765)
        assertEquals(8765, config.valueAt(index))
        assertEquals(index + 1, config.wheelIndex(8766, index))
        val last = config.wheelIndex(9999)
        assertEquals(last + 1, config.wheelIndex(0, last))
    }

    @Test
    fun singleValueWheelDoesNotWrapOrMove() {
        val config = NumberPickerConfig(minimum = 5, maximum = 5)
        assertEquals(5, config.step(5, 1))
        assertEquals(5, config.step(5, -1))
        assertEquals(0, config.wheelIndex(5))
    }

    @Test
    fun invalidRangeAndMismatchedLabelsAreRejectedBeforeShowing() {
        for (create in
            listOf(
                { NumberPickerConfig(minimum = -1, maximum = 10) },
                { NumberPickerConfig(minimum = 10, maximum = 9) },
                { NumberPickerConfig(minimum = 0, maximum = 1, labels = listOf("one")) },
            )) {
            try {
                create()
                fail("Expected invalid picker configuration to be rejected")
            } catch (_: IllegalArgumentException) {}
        }
    }
}
