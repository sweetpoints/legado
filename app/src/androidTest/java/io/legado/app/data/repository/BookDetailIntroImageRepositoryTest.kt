package io.legado.app.data.repository

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fi.iki.elonen.NanoHTTPD
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookDetailIntroImageRepositoryTest {
    private fun fixture() =
        InstrumentationRegistry.getInstrumentation().context.assets.open("photo-animated.gif").use {
            it.readBytes()
        }

    @Test
    fun inlineGifWithLegacyImageOptionsReturnsOriginalAnimatedBytesRatherThanARecyclableFrame() =
        runBlocking {
            val bytes = fixture()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val source = "data:image/gif;base64,$encoded,{\"width\":120,\"click\":\"book.name\"}"
            val repository =
                BookDetailIntroImageRepository(ApplicationProvider.getApplicationContext<Context>())
            val loaded = withContext(Dispatchers.Main) { repository.image(source, null) }
            assertEquals("image/gif", loaded.mime)
            assertArrayEquals(bytes, loaded.bytes)
        }

    @Test
    fun actualGlideFileRequestRetainsSourceHeaderAndEntireGifAfterTargetIsClearedOnMain() =
        runBlocking {
            val bytes = fixture()
            val header = AtomicReference<String?>()
            val server =
                object : NanoHTTPD("127.0.0.1", 0) {
                        override fun serve(session: IHTTPSession): Response {
                            header.set(session.headers["x-book-intro"])
                            return newFixedLengthResponse(
                                Response.Status.OK,
                                "image/gif",
                                bytes.inputStream(),
                                bytes.size.toLong(),
                            )
                        }
                    }
                    .apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            val root = "http://127.0.0.1:${server.listeningPort}"
            val source =
                BookSource(
                    bookSourceUrl = "$root/source-${UUID.randomUUID()}",
                    bookSourceName = "Fixture",
                    header = "{\"X-Book-Intro\":\"fixture\"}",
                )
            try {
                withContext(Dispatchers.IO) { appDb.bookSourceDao.insert(source) }
                val repository =
                    BookDetailIntroImageRepository(
                        ApplicationProvider.getApplicationContext<Context>()
                    )
                val loaded =
                    withContext(Dispatchers.Main) {
                        repository.image(
                            "$root/image-${UUID.randomUUID()}.gif",
                            source.bookSourceUrl,
                        )
                    }
                assertEquals("fixture", header.get())
                assertEquals("image/gif", loaded.mime)
                assertArrayEquals(bytes, loaded.bytes)
            } finally {
                withContext(Dispatchers.IO) { appDb.bookSourceDao.delete(source) }
                server.stop()
            }
        }
}
