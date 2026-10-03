package io.legado.app.data.repository

import io.legado.app.data.preferences.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentSearchRepositoryTest {
    private val book = ContentSearchBook("book", "Book", 3, false, "full-book-json")

    private fun chapter(index: Int) =
        ContentSearchChapter(index, "file-$index", "full-chapter-$index")

    @Test
    fun onlineSearchReadsOnlyCachedChaptersInDaoOrderAndPublishesCumulativeCountsWithoutBodyPayloads() =
        runTest {
            val store = Fake(book, listOf(chapter(3), chapter(1), chapter(2)))
            val repo =
                DefaultContentSearchRepository(
                    store,
                    Options(),
                    StandardTestDispatcher(testScheduler),
                )
            val updates = repo.search(book, "q") { setOf("file-3", "file-2") }.toList()
            assertEquals(listOf(3, 2), store.readChapters)
            assertEquals(listOf(3, 2), updates.last().results.map { it.chapterIndex })
            assertEquals(listOf(0, 1, 1, 2, 2), updates.map { it.results.size })
            assertFalse(updates.last().running)
            assertEquals(3, updates.last().searchedChapters)
            assertEquals(3, updates.last().totalChapters)
            assertEquals("Title 3", updates.last().results.first().chapterTitle)
        }

    @Test
    fun localSearchIncludesUncachedChaptersAndMissingContentDoesNotCreateFalseResult() = runTest {
        val local = book.copy(local = true)
        val store = Fake(local, listOf(chapter(0), chapter(1))).apply { missing += 0 }
        val result =
            DefaultContentSearchRepository(store, Options(), StandardTestDispatcher(testScheduler))
                .search(local, "q") { emptySet() }
                .last()
        assertEquals(listOf(0, 1), store.readChapters)
        assertEquals(listOf(1), result.results.map { it.chapterIndex })
    }

    @Test
    fun downloadedDuringSearchBecomesEligibleAndOptionChangesApplyToSubsequentChapter() = runTest {
        val store = Fake(book, listOf(chapter(0), chapter(1)))
        val options = Options()
        val repo =
            DefaultContentSearchRepository(store, options, StandardTestDispatcher(testScheduler))
        val updates =
            repo
                .search(book, "q") {
                    if (0 in store.readChapters) {
                        options.replace(true)
                        options.regex(true)
                        setOf("file-0", "file-1")
                    } else setOf("file-0")
                }
                .toList()
        assertEquals(listOf(false, true), store.replaceFlags)
        assertEquals(listOf(false, true), updates.last().results.map { it.isRegex })
        assertEquals(2, updates.last().results.size)
    }

    @Test
    fun laterReadFailureKeepsAlreadyEmittedResultsAndCancelledNonCooperativeReadCannotPublishLateResult() =
        runTest {
            val store = Fake(book, listOf(chapter(0), chapter(1))).apply { failureAt = 1 }
            val repo =
                DefaultContentSearchRepository(
                    store,
                    Options(),
                    StandardTestDispatcher(testScheduler),
                )
            val received = mutableListOf<ContentSearchUpdate>()
            try {
                repo.search(book, "q") { setOf("file-0", "file-1") }.collect { received += it }
                fail("Expected read failure")
            } catch (_: IllegalStateException) {}
            assertEquals(listOf(0), received.last().results.map { it.chapterIndex })
            assertTrue(received.last().running)
            val gate = CompletableDeferred<Unit>()
            store.failureAt = null
            store.beforeRead = { withContext(NonCancellable) { gate.await() } }
            val next = mutableListOf<ContentSearchUpdate>()
            val job = launch { repo.search(book, "q") { setOf("file-0") }.collect { next += it } }
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            job.join()
            assertTrue(next.all { it.results.isEmpty() })
        }

    @Test
    fun loadAndSearchAccessActualIoThreadAndSessionPersistenceKeepsFullPrivateMetadata() =
        runBlocking {
            val caller = Thread.currentThread()
            val store = Fake(book, listOf(chapter(0)))
            val repo = DefaultContentSearchRepository(store, Options(), Dispatchers.IO)
            val loaded = repo.load(book.url)
            assertEquals(book, loaded.book)
            assertEquals(setOf("file-0"), loaded.cacheNames)
            val results = repo.search(loaded.book, "q") { loaded.cacheNames }.last().results
            val snapshot =
                ContentSearchSession(
                    book.url,
                    query = "q",
                    results = results,
                    options = ContentSearchOptions(true, true),
                    pendingResult = results.single().id,
                    revision = 50,
                )
            repo.create("session", snapshot)
            repo.write("session", snapshot)
            assertEquals(snapshot, repo.read("session"))
            repo.release("session")
            assertNull(store.stored)
            assertTrue(store.threads.isNotEmpty())
            assertTrue(store.threads.all { it !== caller })
        }

    @Test
    fun missingBookFailsBeforeCacheOrChapterReadsAndBlankQueryCannotStartSearch() = runTest {
        val store = Fake(null, listOf(chapter(0)))
        val repo =
            DefaultContentSearchRepository(store, Options(), StandardTestDispatcher(testScheduler))
        try {
            repo.load("missing")
            fail("Expected missing book")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, store.cacheReads)
        assertTrue(store.readChapters.isEmpty())
        try {
            repo.search(book, " ") { emptySet() }.toList()
            fail("Expected blank query rejection")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, store.chapterReads)
    }

    private class Options : ContentSearchOptionsRepository {
        private var value = ContentSearchOptions()

        override fun current() = value

        override fun replace(value: Boolean) =
            this.value.copy(replace = value).also { this.value = it }

        override fun regex(value: Boolean) = this.value.copy(regex = value).also { this.value = it }

        override fun restore(value: ContentSearchOptions) = value.also { this.value = it }
    }

    private class Fake(
        private val book: ContentSearchBook?,
        private val chapters: List<ContentSearchChapter>,
    ) : ContentSearchStore {
        val readChapters = mutableListOf<Int>()
        val replaceFlags = mutableListOf<Boolean>()
        val threads = mutableListOf<Thread>()
        var stored: ContentSearchSession? = null
        val missing = mutableSetOf<Int>()
        var cacheReads = 0
        var chapterReads = 0
        var failureAt: Int? = null
        var beforeRead: suspend () -> Unit = {}

        private fun touch() {
            threads += Thread.currentThread()
        }

        override suspend fun book(url: String): ContentSearchBook? {
            touch()
            return book
        }

        override suspend fun cacheNames(book: ContentSearchBook): Set<String> {
            touch()
            cacheReads++
            return setOf("file-0")
        }

        override suspend fun chapters(book: ContentSearchBook): List<ContentSearchChapter> {
            touch()
            chapterReads++
            return chapters
        }

        override suspend fun process(
            book: ContentSearchBook,
            chapter: ContentSearchChapter,
            replace: Boolean,
        ): ContentSearchChapterText? {
            touch()
            readChapters += chapter.index
            replaceFlags += replace
            beforeRead()
            if (chapter.index == failureAt) error("chapter failed")
            return if (chapter.index in missing) null
            else ContentSearchChapterText(chapter.index, "Title ${chapter.index}", "Body q")
        }

        override suspend fun read(session: String): ContentSearchSession? {
            touch()
            return stored
        }

        override suspend fun create(
            session: String,
            snapshot: ContentSearchSession,
        ): ContentSearchSession {
            touch()
            return (stored ?: snapshot.also { stored = it })
        }

        override suspend fun write(session: String, snapshot: ContentSearchSession) {
            touch()
            stored = snapshot
        }

        override suspend fun release(session: String) {
            touch()
            stored = null
        }
    }
}
