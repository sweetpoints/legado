package io.legado.app.ui.book.read.config

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.AppReaderBackgroundPreviewRepository
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.getCompatColor
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReaderBackgroundPreviewRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = AppReaderBackgroundPreviewRepository(context)

    private fun configuration(path: String, type: Int) =
        GSON.toJson(
            ReadBookConfig.Config(
                bgStr = path,
                bgStrNight = path,
                bgStrEInk = path,
                bgType = type,
                bgTypeNight = type,
                bgTypeEInk = type,
            )
        )

    @Test
    fun colorPreviewAndMalformedConfigUseCorrectColorsWithoutChangingReaderNinePatchFlag() =
        runBlocking {
            val previous = ReadBookConfig.isNineBgImg
            try {
                ReadBookConfig.isNineBgImg = true
                assertEquals(
                    Color.MAGENTA,
                    (repository.load(configuration("#FF00FF", 0), 100, 150) as ColorDrawable).color,
                )
                assertEquals(
                    context.getCompatColor(R.color.background),
                    (repository.load("invalid json", 100, 150) as ColorDrawable).color,
                )
                assertEquals(
                    context.getCompatColor(R.color.background),
                    (repository.load(configuration("#FF00FF", 0), 0, 0) as ColorDrawable).color,
                )
                assertTrue(ReadBookConfig.isNineBgImg)
            } finally {
                ReadBookConfig.isNineBgImg = previous
            }
        }

    @Test
    fun boundedFilePreviewUsesRealImageAndKeepsReaderBackgroundStateUntouched() = runBlocking {
        val file = File.createTempFile("style-preview-", ".png", context.cacheDir)
        val bitmap =
            Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val previous = ReadBookConfig.isNineBgImg
        try {
            ReadBookConfig.isNineBgImg = true
            val drawable = repository.load(configuration(file.absolutePath, 2), 100, 150)
            val rendered = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 100, 150)
            drawable.draw(Canvas(rendered))
            assertEquals(Color.CYAN, rendered.getPixel(50, 75))
            assertTrue(ReadBookConfig.isNineBgImg)
            rendered.recycle()
        } finally {
            file.delete()
            ReadBookConfig.isNineBgImg = previous
        }
    }

    @Test
    fun missingNinePatchFileFallsBackAndNeverMarksTheActiveReaderAsNinePatch() = runBlocking {
        val previous = ReadBookConfig.isNineBgImg
        try {
            ReadBookConfig.isNineBgImg = false
            val result =
                repository.load(
                    configuration(
                        File(context.cacheDir, "missing-style-preview.9.png").absolutePath,
                        2,
                    ),
                    100,
                    150,
                )
            assertTrue(result is ColorDrawable)
            assertFalse(ReadBookConfig.isNineBgImg)
        } finally {
            ReadBookConfig.isNineBgImg = previous
        }
    }
}
