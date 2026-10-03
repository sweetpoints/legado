package io.legado.app.ui.book.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaViewportTransformTest {
    private val geometry = MangaViewportGeometry(400f, 600f)

    @Test
    fun pinchKeepsOriginalHalfToThreeScaleBoundsAndFocalPoint() {
        assertEquals(.5f, geometry.pinch(MangaViewportTransform(), .1f, 0f, 0f).scale)
        assertEquals(3f, geometry.pinch(MangaViewportTransform(), 10f, 0f, 0f).scale)
        val zoomed = geometry.pinch(MangaViewportTransform(), 2f, 100f, 100f)
        assertEquals(-100f, zoomed.translationX)
        assertEquals(-100f, zoomed.translationY)
    }

    @Test
    fun doubleTapUsesTwoTimesZoomAndResetsEveryNonDefaultScale() {
        val doubled = geometry.doubleTap(MangaViewportTransform(), 100f, 0f)
        assertEquals(2f, doubled.scale)
        assertEquals(-100f, doubled.translationX)
        assertEquals(MangaViewportTransform(), geometry.doubleTap(doubled, -100f, 0f))
        assertEquals(
            MangaViewportTransform(),
            geometry.doubleTap(MangaViewportTransform(.5f), 0f, 0f),
        )
    }

    @Test
    fun panClampsViewportEdgesAndCannotMoveZoomedOutViewport() {
        val panned = geometry.pan(MangaViewportTransform(2f), 1000f, -1000f)
        assertEquals(200f, panned.translationX)
        assertEquals(-300f, panned.translationY)
        val small = geometry.pan(MangaViewportTransform(.5f), 1000f, 1000f)
        assertEquals(0f, small.translationX)
        assertEquals(0f, small.translationY)
    }

    @Test
    fun invalidGeometryInputNeverProducesNonFiniteDrawingCoordinates() {
        val current = MangaViewportTransform(2f)
        assertEquals(current, geometry.pinch(current, Float.NaN, 0f, 0f))
        val invalid =
            geometry.bounded(MangaViewportTransform(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN))
        assertEquals(1f, invalid.scale)
        assertTrue(invalid.translationX.isFinite())
        assertTrue(invalid.translationY.isFinite())
    }
}
