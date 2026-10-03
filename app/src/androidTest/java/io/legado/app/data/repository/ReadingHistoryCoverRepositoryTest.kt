package io.legado.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.image.CoverImage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class ReadingHistoryCoverRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var directory: File

    @Before
    fun before() {
        directory = File(context.cacheDir, "history-cover-${UUID.randomUUID()}")
        directory.mkdirs()
    }

    @After
    fun after() {
        directory.deleteRecursively()
    }

    private fun png(name: String, color: Int): String {
        val file = File(directory, "$name.png")
        Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        return file.path
    }

    private fun center(result: ReadingHistoryCoverResult): Int {
        val bitmap = (result.image as CoverImage.Static).bitmap
        assertFalse(bitmap.isRecycled)
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    @Test
    fun actualGlideKeepsCurrentBookCoverBeforeSnapshotAndThemeFallbackWithIndependentBitmap() =
        runBlocking {
            val current = png("current", Color.GREEN)
            val snapshot = png("snapshot", Color.RED)
            val fallback = png("fallback", Color.BLUE)
            val result =
                GlideReadingHistoryCoverRepository(context)
                    .load(ReadingHistoryCover(current, snapshot, null, false), fallback, 48, 64)
            assertFalse(result.placeholder)
            assertEquals(Color.GREEN, center(result))
            assertEquals(48, (result.image as CoverImage.Static).bitmap.width)
        }

    @Test
    fun failedCurrentUsesHistorySnapshotThenSelectedFallbackAndUnsetFallbackStaysWhiteWithoutTitle() =
        runBlocking {
            val snapshot = png("snapshot", Color.RED)
            val fallback = png("fallback", Color.BLUE)
            val repository = GlideReadingHistoryCoverRepository(context)
            assertEquals(
                Color.RED,
                center(
                    repository.load(
                        ReadingHistoryCover("missing-current", snapshot, null, false),
                        fallback,
                        48,
                        64,
                    )
                ),
            )
            assertEquals(
                Color.BLUE,
                center(
                    repository.load(
                        ReadingHistoryCover(null, "missing-snapshot", null, false),
                        fallback,
                        48,
                        64,
                    )
                ),
            )
            val empty = repository.load(ReadingHistoryCover(null, null, null, false), null, 48, 64)
            assertTrue(empty.placeholder)
            assertEquals(Color.WHITE, center(empty))
        }
}
