package io.legado.app.ui.book.read

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderContextPopupPositionTest {
    @Test
    fun pageCoordinatesIncludeHostWindowOriginAndDropdownGap() {
        assertEquals(IntOffset(50, 124), position(40f, 90f, anchor = IntRect(10, 30, 210, 530)))
    }

    @Test
    fun nearBottomHighlightActionsMoveAboveTheAnchor() {
        assertEquals(IntOffset(40, 356), position(40f, 480f))
    }

    @Test
    fun imageActionsKeepTheirBottomAtTheLongPressPoint() {
        assertEquals(IntOffset(40, 80), position(40f, 200f, preferAbove = true))
    }

    @Test
    fun rtlStartAlignmentAndOversizedWindowsRemainReachable() {
        assertEquals(IntOffset(40, 104), position(120f, 100f, direction = LayoutDirection.Rtl))
        assertEquals(IntOffset.Zero, position(200f, 300f, window = IntSize(40, 50)))
    }

    private fun position(
        x: Float,
        y: Float,
        anchor: IntRect = IntRect(0, 0, 200, 500),
        window: IntSize = IntSize(200, 500),
        direction: LayoutDirection = LayoutDirection.Ltr,
        preferAbove: Boolean = false,
    ) =
        readerContextPopupOffset(
            ReaderContextMenuState(x, y, emptyList(), preferAbove),
            anchor,
            window,
            IntSize(80, 120),
            direction,
            4,
        )
}
