package io.legado.app.data.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.load.resource.gif.GifDrawable
import io.legado.app.data.repository.CoverConfiguration
import io.legado.app.data.repository.CoverRequest
import io.legado.app.model.CoverFontSizes
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoverImageDataTest {
    private fun configuration() =
        CoverConfiguration(
            Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) },
            backgroundColor = Color.WHITE,
            accentColor = Color.BLACK,
        )

    private fun opaquePixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { Color.alpha(it) != 0 }
    }

    @Test
    fun horizontalAndVerticalRendererProduceOwnedTitleAndAuthorPixels() {
        val renderer = CoverTitleRenderer()
        val request = CoverRequest(name = "A long title 测试😀", author = "An author")
        val config = configuration()
        val vertical = renderer.render(request, config, 120, 160)
        val horizontal = renderer.render(request, config.copy(horizontal = true), 120, 160)
        assertEquals(120, vertical.width)
        assertEquals(160, vertical.height)
        assertTrue(opaquePixels(vertical) > 0)
        assertTrue(opaquePixels(horizontal) > 0)
        assertFalse(vertical.isMutable)
        assertFalse(horizontal.isMutable)
        assertFalse(vertical.sameAs(horizontal))
        assertSame(vertical, renderer.render(request, config, 120, 160))
    }

    @Test
    fun authorToggleCustomFontSizesAndFontStampRegenerateCache() {
        val renderer = CoverTitleRenderer()
        val request = CoverRequest(name = "Title", author = "Author")
        val config = configuration()
        val withAuthor = renderer.render(request, config, 120, 160)
        val withoutAuthor = renderer.render(request, config.copy(drawAuthor = false), 120, 160)
        assertTrue(opaquePixels(withAuthor) > opaquePixels(withoutAuthor))
        val custom =
            renderer.render(
                request,
                config.copy(fontSizes = CoverFontSizes(150, 80, 120, 80)),
                120,
                160,
            )
        assertFalse(withAuthor.sameAs(custom))
        assertNotSame(
            withAuthor,
            renderer.render(request, config.copy(fontCacheKey = "new-font-stamp"), 120, 160),
        )
    }

    @Test
    fun staticLoadedBitmapRemainsValidAfterGlideTargetWasCleared() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("compose-cover-", ".png", context.cacheDir)
        val source =
            Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val loaded =
                GlideCoverImageLoader(context)
                    .load(CoverRequest(file.absolutePath), configuration(), 30, 40)
            assertFalse(loaded.needsTitle)
            val bitmap = (loaded.image as CoverImage.Static).bitmap
            assertFalse(bitmap.isRecycled)
            assertFalse(bitmap.isMutable)
            assertEquals(Color.RED, bitmap.getPixel(15, 20))
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun emptyDefaultAndFailedPathsUseDefaultWithTitle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = GlideCoverImageLoader(context)
        val config = configuration()
        assertTrue(loader.load(CoverRequest(), config, 30, 40).needsTitle)
        assertTrue(loader.load(CoverRequest("/missing/file.jpg"), config, 30, 40).needsTitle)
        val forced =
            loader.load(CoverRequest("/ignored/file.jpg"), config.copy(useDefault = true), 30, 40)
        assertSame(config.defaultBitmap, (forced.image as CoverImage.Static).bitmap)
    }

    @Test
    fun animatedLoaderRetainsActualGifUntilLeaseRelease() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("compose-cover-animation-", ".gif", context.cacheDir)
        instrumentation.context.assets.open("photo-animated.gif").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            val loaded =
                GlideCoverImageLoader(context)
                    .load(CoverRequest(file.absolutePath), configuration(), 30, 40)
            assertFalse(loaded.needsTitle)
            val resource = (loaded.image as CoverImage.Animated).resource
            assertEquals(2, (resource.drawable as GifDrawable).frameCount)
            assertFalse(resource.isReleased)
            resource.release()
            assertTrue(resource.isReleased)
            assertNull(resource.drawable.callback)
        } finally {
            file.delete()
        }
    }
}
