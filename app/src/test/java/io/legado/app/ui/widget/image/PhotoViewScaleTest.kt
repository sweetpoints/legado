package io.legado.app.ui.widget.image

import io.legado.app.ui.widget.dialog.photo.PhotoGeometry
import io.legado.app.ui.widget.dialog.photo.PhotoTransform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Geometry contracts shared by pinch, double tap, pan and fling in the Compose viewer. */
class PhotoViewScaleTest {
    @Test
    fun smallLandscapeImageFitsContainerWidth() {
        assertEquals(4f, PhotoGeometry(200f, 100f, 800f, 600f).fitScale, 0f)
    }

    @Test
    fun smallPortraitImageFitsContainerHeight() {
        assertEquals(3f, PhotoGeometry(100f, 200f, 800f, 600f).fitScale, 0f)
    }

    @Test
    fun largeImageFitsWithoutOverflow() {
        assertEquals(.3f, PhotoGeometry(1000f, 2000f, 800f, 600f).fitScale, .0001f)
    }

    @Test
    fun invalidImageAndViewportDimensionsHaveSafeFallback() {
        assertEquals(1f, PhotoGeometry(0f, 100f, 800f, 600f).fitScale, 0f)
        assertEquals(1f, PhotoGeometry(100f, 100f, 0f, 600f).fitScale, 0f)
    }

    @Test
    fun panClampsEachAxisIndependentlyAndCentersAnAxisThatFits() {
        val geometry = PhotoGeometry(200f, 100f, 800f, 600f)
        assertEquals(
            PhotoTransform(2f, 400f, -100f),
            geometry.bounded(PhotoTransform(2f, 1000f, -1000f)),
        )
        assertEquals(PhotoTransform(1f, 0f, 0f), geometry.bounded(PhotoTransform(1f, 80f, 80f)))
    }

    @Test
    fun zoomKeepsFocalPointFixedAndClampsScale() {
        val geometry = PhotoGeometry(800f, 600f, 800f, 600f)
        assertEquals(
            PhotoTransform(2f, -100f, -50f),
            geometry.transform(PhotoTransform(), 2f, 0f, 0f, 100f, 50f),
        )
        assertEquals(2.5f, geometry.transform(PhotoTransform(), 10f, 0f, 0f, 0f, 0f).scale, 0f)
        assertEquals(
            PhotoTransform(),
            geometry.transform(PhotoTransform(2f, 30f, 30f), .1f, 0f, 0f, 0f, 0f),
        )
    }

    @Test
    fun doubleTapZoomsAroundTapThenRestoresCenteredFit() {
        val geometry = PhotoGeometry(800f, 600f, 800f, 600f)
        val zoomed = geometry.doubleTap(PhotoTransform(), 100f, 50f)
        assertEquals(PhotoTransform(2.5f, -150f, -75f), zoomed)
        assertEquals(PhotoTransform(), geometry.doubleTap(zoomed, 100f, 50f))
    }

    @Test
    fun dragAndFlingUseTheSameBoundaryContract() {
        val geometry = PhotoGeometry(800f, 600f, 800f, 600f)
        var transform = PhotoTransform(2.5f)
        repeat(100) { transform = geometry.transform(transform, 1f, 100f, -100f, 0f, 0f) }
        assertEquals(PhotoTransform(2.5f, 600f, -450f), transform)
        assertEquals(transform, geometry.transform(transform, 1f, 100f, -100f, 0f, 0f))
    }

    @Test
    fun viewportResizeBoundsExistingPan() {
        val portrait = PhotoGeometry(800f, 600f, 600f, 800f)
        assertEquals(
            PhotoTransform(2f, 300f, 50f),
            portrait.bounded(PhotoTransform(2f, 400f, 200f)),
        )
    }

    @Test
    fun nonfiniteOrInvalidZoomDoesNotChangePreview() {
        val geometry = PhotoGeometry(800f, 600f, 800f, 600f)
        val transform = PhotoTransform(2f, 12f, 34f)
        for (zoom in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertEquals(transform, geometry.transform(transform, zoom, 10f, 10f, 0f, 0f))
        }
    }
}
