package io.legado.app.data.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.load.resource.gif.GifDrawable
import io.legado.app.help.glide.progress.OnProgressListener
import io.legado.app.help.glide.progress.ProgressManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaImageRepositoryTest {
    @Test
    fun completedProgressCallbackCannotRemoveReentrantReplacementRequest() {
        val url = "https://example.invalid/reentrant"
        var replacementUpdates = 0
        val replacement: OnProgressListener = { _, _, _, _ -> replacementUpdates++ }
        val original: OnProgressListener = { complete, _, _, _ ->
            if (complete) ProgressManager.addListener(url, replacement)
        }
        try {
            ProgressManager.addListener(url, original)
            ProgressManager.LISTENER.onProgress(url, 100, 100)
            assertEquals(1, replacementUpdates)
            ProgressManager.LISTENER.onProgress(url, 10, 100)
            assertEquals(2, replacementUpdates)
        } finally {
            ProgressManager.removeListener(url, original)
            ProgressManager.removeListener(url, replacement)
        }
    }

    @Test
    fun lateProgressCleanupPreservesReplacementListenerWithUrlOptions() {
        val url = "https://example.invalid/image, {\"headers\":{}}"
        val oldListener: OnProgressListener = { _, _, _, _ -> }
        var updates = 0
        val replacement: OnProgressListener = { _, _, _, _ -> updates++ }
        try {
            ProgressManager.addListener(url, oldListener)
            ProgressManager.addListener(url, replacement)
            ProgressManager.removeListener(url, oldListener)
            ProgressManager.LISTENER.onProgress("https://example.invalid/image", 10, 100)
            assertEquals(2, updates)
            ProgressManager.removeListener(url, replacement)
            ProgressManager.LISTENER.onProgress("https://example.invalid/image", 20, 100)
            assertEquals(2, updates)
        } finally {
            ProgressManager.removeListener(url, replacement)
            ProgressManager.removeListener(url, oldListener)
        }
    }

    @Test
    fun actualGifKeepsAllFramesUntilLeaseReleased() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("manga-animation-", ".gif", context.cacheDir)
        instrumentation.context.assets.open("photo-animated.gif").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        try {
            val image =
                GlideMangaImageRepository(context)
                    .load(MangaImageRequest("missing-book", null, file.absolutePath))
            assertEquals(2, (image.drawable as GifDrawable).frameCount)
            assertFalse(image.isReleased)
            image.release()
            image.release()
            assertTrue(image.isReleased)
            assertNull(image.drawable.callback)
        } finally {
            file.delete()
        }
    }

    @Test
    fun actualWebpRetainsDecodedPixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("manga-webp-", ".webp", context.cacheDir)
        val bitmap =
            Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
            }
        try {
            file.outputStream().use {
                @Suppress("DEPRECATION") bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it)
            }
            val image =
                GlideMangaImageRepository(context)
                    .load(MangaImageRequest("missing-book", null, file.absolutePath))
            try {
                val pixel = (image.drawable as BitmapDrawable).bitmap.getPixel(0, 0)
                assertTrue(Color.red(pixel) > 240)
                assertTrue(Color.green(pixel) < 15)
            } finally {
                image.release()
            }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun staticImageRemainsLeasedAndPreloadUsesOriginalFilePipeline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("manga-static-", ".png", context.cacheDir)
        val bitmap =
            Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val repository = GlideMangaImageRepository(context)
            val request = MangaImageRequest("missing-book", null, file.absolutePath)
            repository.preload(request)
            val image = repository.load(request)
            try {
                assertEquals(Color.RED, (image.drawable as BitmapDrawable).bitmap.getPixel(0, 0))
                assertFalse(image.isReleased)
            } finally {
                image.release()
            }
            assertTrue(image.isReleased)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }
}
