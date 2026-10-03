package io.legado.app.ui.dict

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.repository.RoomDictionaryLookupRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DictionaryLookupRepositoryImageTest {
    @Suppress("DEPRECATION")
    @Test
    fun glideFileLeaseKeepsOriginalGifFramesAfterClear() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file =
            File.createTempFile("dictionary-image-", ".gif", instrumentation.targetContext.cacheDir)
        instrumentation.context.assets.open("photo-animated.gif").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val image =
                RoomDictionaryLookupRepository(instrumentation.targetContext)
                    .image(file.absolutePath)
            assertEquals("image/gif", image.mime)
            val movie = checkNotNull(Movie.decodeByteArray(image.bytes, 0, image.bytes.size))
            movie.setTime(0)
            movie.draw(Canvas(bitmap), 0f, 0f)
            val first = bitmap.getPixel(0, 0)
            movie.setTime(150)
            movie.draw(Canvas(bitmap), 0f, 0f)
            assertNotEquals(first, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun base64SvgWithLegacyWidthOptionsKeepsOriginalBytes() = runBlocking {
        val xml =
            "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='10'><rect width='20' height='10' fill='red'/></svg>"
        val source =
            "data:image/svg+xml;base64," +
                Base64.encodeToString(xml.toByteArray(), Base64.NO_WRAP) +
                ",{\"width\":\"80%\"}"
        val image =
            RoomDictionaryLookupRepository(
                    InstrumentationRegistry.getInstrumentation().targetContext
                )
                .image(source)
        assertEquals("image/svg+xml", image.mime)
        assertEquals(xml, image.bytes.toString(Charsets.UTF_8))
    }
}
