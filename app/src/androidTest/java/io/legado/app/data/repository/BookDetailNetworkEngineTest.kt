package io.legado.app.data.repository

import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.TocRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BookDetailNetworkEngineTest {
    @Test
    fun mainCallerUsesActualWebBookHttpAndTocRulesWithoutInstallingSearchPreviewInRoom() =
        runBlocking {
            val requests = AtomicInteger()
            val server =
                object : NanoHTTPD("127.0.0.1", 0) {
                        override fun serve(session: IHTTPSession): Response {
                            requests.incrementAndGet()
                            return newFixedLengthResponse(
                                if (session.uri == "/toc")
                                    "<div class='chapter'><a href='/chapter1'>Chapter One</a></div><div class='chapter'><a href='/chapter2'>Chapter Two</a></div>"
                                else
                                    "<h1>Parsed book</h1><span class='author'>Parsed author</span><a class='toc' href='/toc'>Contents</a>"
                            )
                        }
                    }
                    .apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            try {
                val origin = "http://127.0.0.1:${server.listeningPort}"
                val source =
                    BookSource(
                        bookSourceUrl = origin,
                        bookSourceName = "Fixture",
                        ruleBookInfo =
                            BookInfoRule(
                                name = "h1@text",
                                author = ".author@text",
                                tocUrl = ".toc@href",
                            ),
                        ruleToc =
                            TocRule(
                                chapterList = ".chapter",
                                chapterName = "a@text",
                                chapterUrl = "a@href",
                            ),
                    )
                val original =
                    BookDetailBook.from(
                        Book(bookUrl = "$origin/details", name = "Original", origin = origin)
                    )
                val engine =
                    object : BookDetailNetworkEngine by DefaultBookDetailNetworkEngine {
                        override suspend fun info(
                            book: Book,
                            source: BookSource?,
                            canRename: Boolean,
                        ): Book {
                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                            return DefaultBookDetailNetworkEngine.info(book, source, canRename)
                        }

                        override suspend fun toc(
                            book: Book,
                            source: BookSource?,
                            runPreUpdate: Boolean,
                            fromBookInfo: Boolean,
                        ): List<io.legado.app.data.entities.BookChapter> {
                            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                            return DefaultBookDetailNetworkEngine.toc(
                                book,
                                source,
                                runPreUpdate,
                                fromBookInfo,
                            )
                        }
                    }
                val result =
                    withContext(Dispatchers.Main) {
                        EngineBookDetailNetworkRepository(engine)
                            .info(original, BookDetailSource.from(source), true, false)
                    }
                assertEquals("Parsed book", result.book.name)
                assertEquals("Parsed author", result.book.author)
                assertEquals(listOf("Chapter One", "Chapter Two"), result.chapters.map { it.title })
                assertEquals(
                    listOf("$origin/chapter1", "$origin/chapter2"),
                    result.chapters.map { it.url },
                )
                assertEquals(2, requests.get())
                assertEquals("Original", original.name)
                val direct =
                    withContext(Dispatchers.Main) {
                        EngineBookDetailNetworkRepository(engine)
                            .toc(result.book, BookDetailSource.from(source), false)
                    }
                assertEquals(result.chapters.map { it.url }, direct.chapters.map { it.url })
                assertEquals(3, requests.get())
                assertNull(withContext(Dispatchers.IO) { appDb.bookDao.getBook(original.bookUrl) })
            } finally {
                server.stop()
            }
        }

    @Test
    fun actualCachedWebFileInfoRulePopulatesDownloadOptionsAndFilenameWithoutTocRequest() =
        runBlocking {
            val origin = "https://book-detail-file.invalid"
            val source =
                BookSource(
                    bookSourceUrl = origin,
                    bookSourceName = "File fixture",
                    bookSourceType = BookSourceType.file,
                    ruleBookInfo = BookInfoRule(downloadUrls = "a@href"),
                )
            val book =
                Book(
                        bookUrl = "$origin/details",
                        name = "Original",
                        origin = origin,
                        type = BookType.text or BookType.webFile,
                    )
                    .apply { infoHtml = "<a href='/download/book.txt'>Download</a>" }
            val result =
                withContext(Dispatchers.Main) {
                    EngineBookDetailNetworkRepository()
                        .info(BookDetailBook.from(book), BookDetailSource.from(source), true, false)
                }
            assertTrue(result.chapters.isEmpty())
            assertTrue(result.book.isWebFile)
            val file = result.webFiles.single()
            assertEquals("$origin/download/book.txt", file.url)
            assertEquals("book.txt", file.name)
            assertEquals("txt", file.suffix)
            assertTrue(file.supported)
            assertFalse(file.archive)
            assertEquals(listOf(file.url), result.book.materializeBook().downloadUrls)
        }
}
