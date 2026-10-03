package io.legado.app.data.repository

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupLanImageRepositoryTest {
    @Test
    fun privatelyGeneratedImageIsDecodedWithPixelsAndForeignPathIsRejected() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val file = File(context.cacheDir, "backup-settings-lan/${UUID.randomUUID()}.png")
        try {
            file.parentFile!!.mkdirs()
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                file.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
                recycle()
            }
            val repository = FileBackupLanImageRepository(context)
            val decoded = repository.load(file.path)
            try {
                assertEquals(16, decoded.width)
                assertEquals(Color.BLUE, decoded.getPixel(2, 2))
            } finally {
                decoded.recycle()
            }
            assertTrue(
                runCatching { repository.load(File(context.cacheDir, "foreign.png").path) }
                    .isFailure
            )
        } finally {
            file.delete()
        }
    }
}
