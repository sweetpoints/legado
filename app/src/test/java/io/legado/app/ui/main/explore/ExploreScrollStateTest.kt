package io.legado.app.ui.main.explore

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreScrollStateTest {
    @Test
    fun explicitWrapBeforeStartsAnotherControlRow() {
        assertEquals(
            listOf(listOf(0), listOf(1, 2)),
            exploreControlRows(
                listOf(20, 20, 20),
                listOf(
                    ExploreControlStyle(),
                    ExploreControlStyle(wrapBefore = true),
                    ExploreControlStyle(),
                ),
                100,
                4,
            ),
        )
    }

    @Test
    fun fullWidthSourceControlsRetainOneControlPerRow() {
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2)),
            exploreControlRows(
                listOf(100, 100, 100),
                List(3) { ExploreControlStyle(basis = 1f) },
                100,
                4,
            ),
        )
    }

    @Test
    fun rowWrappingIncludesActualSpaceBetweenControls() {
        assertEquals(
            listOf(listOf(0, 1), listOf(2)),
            exploreControlRows(listOf(48, 48, 48), List(3) { ExploreControlStyle() }, 100, 4),
        )
    }
}
