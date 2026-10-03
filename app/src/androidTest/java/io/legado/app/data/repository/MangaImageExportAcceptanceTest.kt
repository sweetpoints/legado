package io.legado.app.data.repository

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.GSON
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MangaImageExportAcceptanceTest {
    @Test
    fun acceptedCapturedImageCompletesAfterCallerCancellation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val book = Book(bookUrl = "manga-export-${UUID.randomUUID()}", name = "captured")
        val imageUrl = "https://export.invalid/${UUID.randomUUID()}.png"
        val directory = File(context.cacheDir, "manga-export-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val bytes =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).let { bitmap ->
                try {
                    ByteArrayOutputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        BookHelp.writeImage(book, imageUrl, bytes)
        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        executor.submit {
            entered.countDown()
            gate.await(30, TimeUnit.SECONDS)
        }
        assertTrue(entered.await(30, TimeUnit.SECONDS))
        executor.asCoroutineDispatcher().use { dispatcher ->
            try {
                val repository = DefaultMangaReaderOperationsRepository(dispatcher)
                val request =
                    MangaImageSaveRequest(
                        book.bookUrl,
                        GSON.toJson(book),
                        null,
                        imageUrl,
                        Uri.fromFile(directory).toString(),
                    )
                // Acceptance occurs before the IO gate, then the real caller is disposed.
                val save =
                    launch(start = CoroutineStart.UNDISPATCHED) { repository.saveImage(request) }
                save.cancel()
                gate.countDown()
                save.join()
                val target = File(directory, BookHelp.getImage(book, imageUrl).name)
                assertTrue(target.isFile)
                assertArrayEquals(bytes, target.readBytes())
            } finally {
                gate.countDown()
                BookHelp.clearCache(book)
                directory.deleteRecursively()
            }
        }
    }
}
