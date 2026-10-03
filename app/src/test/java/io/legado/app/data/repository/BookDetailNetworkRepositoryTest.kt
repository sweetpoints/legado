package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailNetworkRepositoryTest {
    private fun book(type: Int = BookType.text) =
        BookDetailBook.from(
            Book(
                bookUrl = "book",
                name = "Original",
                author = "Author",
                origin = "source",
                type = type,
            )
        )

    private val source =
        BookDetailSource.from(BookSource(bookSourceUrl = "source", bookSourceName = "Source"))

    @Test
    fun fullInfoRunsExistingTocStageWithRequestedFlagsAndReturnsDetachedImmutableSnapshots() =
        runTest {
            val engine = Engine()
            val repo =
                EngineBookDetailNetworkRepository(engine, StandardTestDispatcher(testScheduler))
            val original = book()
            val result = repo.info(original, source, false, true)
            assertEquals(listOf("info:false", "toc:true:true"), engine.calls)
            assertEquals("Parsed", result.book.name)
            assertEquals("Chapter", result.chapters.single().title)
            assertEquals("Original", original.name)
            engine.native!!.name = "Late engine mutation"
            engine.chapter!!.title = "Late title"
            assertEquals("Parsed", result.book.materializeBook().name)
            assertEquals("Chapter", result.chapters.single().title)
        }

    @Test
    fun webFileResultResolvesFilesWithoutRequestingTocAndRetainsDownloadUrlAndOriginalIdentity() =
        runTest {
            val engine = Engine()
            val repo =
                EngineBookDetailNetworkRepository(engine, StandardTestDispatcher(testScheduler))
            val result = repo.info(book(BookType.text or BookType.webFile), source, true, true)
            assertEquals(listOf("info:true", "files"), engine.calls)
            assertTrue(result.chapters.isEmpty())
            assertEquals(listOf(BookDetailWebFile("raw,url-options", "book.epub")), result.webFiles)
            assertEquals("已下载", result.book.latestChapterTitle)
        }

    @Test
    fun localInfoSkipsNetworkPreUpdateButRefreshTocUsesExplicitStageFlag() = runTest {
        val engine = Engine()
        val repo = EngineBookDetailNetworkRepository(engine, StandardTestDispatcher(testScheduler))
        repo.info(book(BookType.local or BookType.text), null, true, true)
        assertEquals(listOf("info:true", "toc:false:true"), engine.calls)
        engine.calls.clear()
        repo.toc(book(), source, true)
        assertEquals(listOf("toc:true:false"), engine.calls)
    }

    @Test
    fun failedChapterStageDoesNotMutateInputOrLeavePartiallyPublishedResult() = runTest {
        val engine = Engine()
        engine.fail = true
        val original = book()
        val repo = EngineBookDetailNetworkRepository(engine, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { repo.info(original, source, true, true) }.isFailure)
        assertEquals("Original", original.name)
        assertEquals("Original", original.materializeBook().name)
    }

    @Test
    fun cancellationAfterNonCooperativeInfoDoesNotStartTocOrReturnLateSnapshot() = runTest {
        val engine = Engine()
        val gate = CompletableDeferred<Unit>()
        engine.gate = gate
        val repo = EngineBookDetailNetworkRepository(engine, StandardTestDispatcher(testScheduler))
        var published = false
        val job = launch {
            repo.info(book(), source, true, true)
            published = true
        }
        runCurrent()
        job.cancel()
        gate.complete(Unit)
        runCurrent()
        job.join()
        assertFalse(published)
        assertEquals(listOf("info:true"), engine.calls)
    }

    @Test
    fun coverRuleOnlyRunsForMissingDisplayCoverAndNeverMutatesOriginalRequest() = runTest {
        val engine = Engine()
        val repo = EngineBookDetailNetworkRepository(engine, StandardTestDispatcher(testScheduler))
        val original = book()
        assertEquals("rule-cover", repo.cover(original)!!.cover.path)
        assertNull(original.cover.path)
        assertEquals(listOf("cover"), engine.calls)
        engine.calls.clear()
        val present = BookDetailBook.from(Book(bookUrl = "book", persistedCoverUrl = "cached"))
        assertNull(repo.cover(present))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun filenameNormalizationKeepsLegacyFallbackDotsAndRejectsUnsafeSuffixes() {
        assertEquals("book.txt", normalizeBookDetailWebFileName("book.txt", "txt"))
        assertEquals("book.TXT", normalizeBookDetailWebFileName("book.TXT", ".txt"))
        assertEquals("book.epub", normalizeBookDetailWebFileName("book.txt", "epub"))
        assertEquals("Version 2.0.txt", normalizeBookDetailWebFileName("Version 2.0", "txt", false))
        assertEquals("book.txt", normalizeBookDetailWebFileName("book.", " txt "))
        assertEquals("book", normalizeBookDetailWebFileName("book", "../txt"))
        assertEquals("book", normalizeBookDetailWebFileName("book", "..."))
    }

    private class Engine : BookDetailNetworkEngine {
        val calls = mutableListOf<String>()
        var native: Book? = null
        var chapter: BookChapter? = null
        var fail = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun info(book: Book, source: BookSource?, canRename: Boolean): Book {
            calls += "info:$canRename"
            native = book
            book.name = "Parsed"
            gate?.let { withContext(NonCancellable) { it.await() } }
            return book
        }

        override suspend fun toc(
            book: Book,
            source: BookSource?,
            runPreUpdate: Boolean,
            fromBookInfo: Boolean,
        ): List<BookChapter> {
            calls += "toc:$runPreUpdate:$fromBookInfo"
            if (fail) error("Chapter failure")
            return listOf(
                BookChapter(bookUrl = "book", url = "chapter", title = "Chapter").also {
                    chapter = it
                }
            )
        }

        override suspend fun files(book: Book, source: BookSource?): List<BookDetailWebFile> {
            calls += "files"
            return listOf(BookDetailWebFile("raw,url-options", "book.epub"))
        }

        override suspend fun cover(book: Book): String? {
            calls += "cover"
            return "rule-cover"
        }
    }
}
