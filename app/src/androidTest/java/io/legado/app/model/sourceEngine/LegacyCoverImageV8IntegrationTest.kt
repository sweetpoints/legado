package io.legado.app.model.sourceEngine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.image.CoverImage
import io.legado.app.data.image.GlideCoverImageLoader
import io.legado.app.data.repository.CoverConfiguration
import io.legado.app.data.repository.CoverRequest
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.NetworkUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual search -> cover request -> Glide pixels, on self-authored local data only. */
@RunWith(AndroidJUnit4::class)
class LegacyCoverImageV8IntegrationTest {
    private fun png(): ByteArray {
        val bitmap =
            Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        return try {
            ByteArrayOutputStream()
                .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                .toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private class Server(private val image: ByteArray) : NanoHTTPD("127.0.0.1", 0) {
        val searches = AtomicInteger()
        val images = AtomicInteger()
        val infos = AtomicInteger()
        val base
            get() = "http://127.0.0.1:$listeningPort"

        val imageRule
            get() = "/cover,{\"headers\":{\"X-Option\":\"fixture\"}}"

        override fun serve(session: IHTTPSession): Response =
            when (session.uri) {
                "/search" -> {
                    searches.incrementAndGet()
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html",
                        "<article><b>Fixture</b><a href='/info'>Book</a><img src='$imageRule'></article>",
                    )
                }
                "/cover" -> {
                    images.incrementAndGet()
                    if (
                        session.headers["x-source"] != "fixture" ||
                            session.headers["x-option"] != "fixture"
                    )
                        newFixedLengthResponse(
                            Response.Status.UNAUTHORIZED,
                            "text/plain",
                            "Missing fixture headers",
                        )
                    else
                        newFixedLengthResponse(
                            Response.Status.OK,
                            "image/png",
                            ByteArrayInputStream(image),
                            image.size.toLong(),
                        )
                }
                "/info" -> {
                    infos.incrementAndGet()
                    newFixedLengthResponse(Response.Status.OK, "text/html", "<img src='/cover'>")
                }
                else ->
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain",
                        "Unknown fixture",
                    )
            }
    }

    private fun source(server: Server, coverRule: String?) =
        BookSource(
            bookSourceUrl = server.base + "/source-${UUID.randomUUID()}",
            bookSourceName = "Cover fixture",
            header = "@js:JSON.stringify({'X-Source':'fixture'})",
            searchUrl = server.base + "/search",
            ruleSearch =
                SearchRule(
                    bookList = "tag.article",
                    name = "tag.b@text",
                    bookUrl = "tag.a@href",
                    coverUrl = coverRule,
                ),
        )

    @Test
    fun legacyCoverOptionsAndSourceHeaderReachActualGlidePixels(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server(png()).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source = source(server, "tag.img@src")
            val fallback =
                Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                }
            var loaded: Bitmap? = null
            appDb.bookSourceDao.insert(source)
            try {
                val book =
                    withTimeout(15_000) { WebBook.searchBookAwait(source, "fixture").single() }
                assertEquals(
                    NetworkUtils.getAbsoluteURL(server.base + "/search", server.imageRule),
                    book.coverUrl,
                )
                assertEquals(source.bookSourceUrl, book.origin)
                val request = CoverRequest.from(book)
                assertEquals(source.bookSourceUrl, request.sourceOrigin)
                val result =
                    withTimeout(15_000) {
                        GlideCoverImageLoader(
                                InstrumentationRegistry.getInstrumentation().targetContext
                            )
                            .load(request, CoverConfiguration(fallback), 12, 18)
                    }
                assertFalse(
                    "The network image must load, rather than the default cover",
                    result.needsTitle,
                )
                loaded = (result.image as CoverImage.Static).bitmap
                assertEquals(Color.MAGENTA, loaded!!.getPixel(6, 9))
                assertEquals(1, server.searches.get())
                assertEquals(1, server.images.get())
                assertEquals(0, server.infos.get())
            } finally {
                loaded?.recycle()
                fallback.recycle()
                server.stop()
                DartSourceEngine.clearSourceState(source)
                appDb.bookSourceDao.delete(source)
            }
        }

    @Test
    fun signedJavaDecodeBytesRetainTheActualPngAndRejectOutOfRangeBytes(): Unit =
        runBlocking(Dispatchers.IO) {
            val bytes = png()
            assertTrue(bytes.any { it < 0 })
            val source =
                BookSource(
                    bookSourceUrl = "https://cover-bytes-${UUID.randomUUID()}.invalid",
                    bookSourceName = "Signed cover fixture",
                    coverDecodeJs =
                        "java.base64DecodeToByteArray('${Base64.encodeToString(bytes, Base64.NO_WRAP)}')",
                )
            try {
                val result =
                    withTimeout(15_000) {
                        ImageUtils.decode("fixture.png", byteArrayOf(0), true, source)
                    }
                assertArrayEquals(bytes, result)
                val bitmap = BitmapFactory.decodeByteArray(checkNotNull(result), 0, result.size)
                assertNotNull(bitmap)
                try {
                    assertEquals(12, bitmap.width)
                    assertEquals(18, bitmap.height)
                    assertEquals(Color.MAGENTA, bitmap.getPixel(6, 9))
                } finally {
                    bitmap.recycle()
                }
                assertArrayEquals(bytes, ImageUtils.decodedBytes(bytes.map { it.toInt() }))
                assertArrayEquals(bytes, ImageUtils.decodedBytes(bytes.map { it.toInt() and 255 }))
                assertThrows(IllegalArgumentException::class.java) {
                    ImageUtils.decodedBytes(listOf(-129))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    ImageUtils.decodedBytes(listOf(256))
                }
                assertThrows(IllegalArgumentException::class.java) {
                    ImageUtils.decodedBytes(listOf(1.5))
                }
            } finally {
                DartSourceEngine.clearSourceState(source)
            }
        }

    @Test
    fun aSearchWithoutACoverRuleDoesNotInventAnImageOrFetchBookInfo(): Unit =
        runBlocking(Dispatchers.IO) {
            val server = Server(png()).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val source = source(server, null)
            try {
                val book =
                    withTimeout(15_000) { WebBook.searchBookAwait(source, "fixture").single() }
                assertEquals("Fixture", book.name)
                assertTrue(book.coverUrl.isNullOrBlank())
                assertNull(CoverRequest.from(book).normalizedPath)
                assertEquals(source.bookSourceUrl, book.origin)
                assertEquals(1, server.searches.get())
                assertEquals(0, server.infos.get())
                assertEquals(0, server.images.get())
            } finally {
                server.stop()
                DartSourceEngine.clearSourceState(source)
            }
        }
}
