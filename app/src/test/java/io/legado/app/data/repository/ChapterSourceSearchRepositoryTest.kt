package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterSourceSearchRepositoryTest {
    private fun row(id: String, order: Int = 0, count: Int = -1, time: Int = -1, score: Int = 0): ChapterSourceSearchRow {
        val book = SearchBook(bookUrl = id, origin = id, originName = "Source-$id", name = "Book", author = "Author", originOrder = order,
            chapterWordCount = count, respondTime = time, chapterWordCountText = if (count >= 0) "[3] Title\n字数：$count" else null,
            coverUrl = "cover-$id", intro = "intro-$id", variable = "{\"custom\":\"$id\"}")
        return ChapterSourceSearchRow(id, id, book.originName, book.name, book.author, book.getDisplayLastChapterTitle(), book.chapterWordCountText,
            count, time, order, score, book.type, GSON.toJson(book))
    }
    @Test fun cachedPolicyPinsCurrentSourceBeforeFiltersAndKeepsUnfilteredMetadataForOptionChanges() = runTest {
        val current = row("current", count = 50, time = 200); val fast = row("fast", count = 150, time = 5); val hidden = row("hidden", count = 400, time = 10)
        val store = Fake().apply { cached = listOf(hidden, fast, current) }; val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val request = ChapterSourceSearchRequest("Book", "Author", loadWordCount = true, sortResponseTime = true, filterMode = 1, minimum = 100, maximum = 200, currentBookUrl = "current")
        val result = repo.cached(request)
        assertEquals(listOf("current", "fast"), result.rows.map { it.id }); assertEquals(store.cached, result.allRows)
        assertEquals(listOf("current", "fast", "hidden"), repo.project(request.copy(filterMode = 0), result.allRows).map { it.id })
        val restored = GSON.fromJson(result.allRows.first().json, SearchBook::class.java)
        assertEquals("cover-hidden", restored.coverUrl); assertEquals("intro-hidden", restored.intro); assertEquals("{\"custom\":\"hidden\"}", restored.variable)
    }
    @Test fun scoreAndSourceScorePrecedeLegacyOriginOrderingAndMeasuredWordCountSort() = runTest {
        val first = row("first", order = 9); val bookScore = row("book-score", order = 10, score = 1); val sourceScore = row("source-score", order = 11)
        val store = Fake().apply { scores["source-score"] = 3 }; val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        assertEquals(listOf("book-score", "source-score", "first"), repo.project(ChapterSourceSearchRequest("Book", "Author"), listOf(first, sourceScore, bookScore)).map { it.id })
        val large = row("large", order = 5, count = 1500, time = 100); val small = row("small", order = 0, count = 200, time = 1)
        assertEquals(listOf("large", "small"), repo.project(ChapterSourceSearchRequest("Book", "Author", loadWordCount = true), listOf(small, large)).map { it.id })
    }
    @Test fun concurrentSearchDeduplicatesStableBookIdsPublishesProgressAndIsolatesSourceFailure() = runTest {
        val store = Fake().apply { sources = listOf("one", "two", "broken"); results["one"] = listOf(row("same", 2)); results["two"] = listOf(row("same", 1), row("other", 3)); failing = setOf("broken") }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler), concurrency = 2)
        val updates = repo.search(ChapterSourceSearchRequest("Book", "Author", group = "Old"), listOf(row("cached"))).toList()
        assertTrue(updates.first().running); assertFalse(updates.last().running); assertEquals(3, updates.last().completed); assertEquals(3, updates.last().total)
        assertEquals(setOf("same", "other"), updates.last().rows.map { it.id }.toSet()); assertEquals(1, store.errors.size)
        assertEquals(listOf("cached"), store.resetRows.map { it.id }); assertEquals("", updates.last().effectiveGroup)
        assertTrue(store.maxActive <= 2); assertEquals(3, store.persisted.size)
    }
    @Test fun perSourceTimeoutCompletesWithoutCancellingOtherSources() = runTest {
        val store = Fake().apply { sources = listOf("slow", "fast"); delays["slow"] = 1000; results["fast"] = listOf(row("fast")) }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler), timeoutMillis = 100, concurrency = 2)
        val final = repo.search(ChapterSourceSearchRequest("Book", "Author"), emptyList()).last()
        assertEquals(listOf("fast"), final.rows.map { it.id }); assertEquals(2, final.completed); assertFalse(final.running); assertTrue(store.errors.isEmpty())
    }
    @Test fun canceledNonCooperativeSearchCannotPersistLateResultsOrPublishFinished() = runTest {
        val store = Fake().apply { sources = listOf("slow"); gate = CompletableDeferred(); results["slow"] = listOf(row("late")) }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler)); val updates = mutableListOf<ChapterSourceSearchUpdate>()
        val task = launch { repo.search(ChapterSourceSearchRequest("Book", "Author"), emptyList()).toList(updates) }
        runCurrent(); task.cancel(); store.gate!!.complete(Unit); task.join()
        assertTrue(store.persisted.isEmpty()); assertTrue(updates.all { it.running }); assertTrue(task.isCancelled)
    }
    @Test fun measurementRefreshOnlyLoadsMissingRowsAndRetainsRowsWhoseSourcesFail() = runTest {
        val old = row("old", count = 500, time = 8); val missing = row("missing"); val broken = row("broken")
        val store = Fake().apply { measured["missing"] = row("missing", count = 700, time = 2); failing = setOf("broken") }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val final = repo.measure(ChapterSourceSearchRequest("Book", "Author", loadWordCount = true), listOf(old, missing, broken), true).last()
        assertEquals(setOf("missing", "broken"), store.measureCalls.toSet()); assertEquals(3, final.allRows.size)
        assertEquals(500, final.allRows.first { it.id == "old" }.wordCount); assertEquals(700, final.allRows.first { it.id == "missing" }.wordCount)
        assertEquals(-1, final.allRows.first { it.id == "broken" }.wordCount); assertFalse(final.running)
    }
    @Test fun measuredCurrentSourceSuppliesRelativeReferenceWhenCachedContentIsUnavailable() = runTest {
        val store = Fake().apply { sources = listOf("one"); results["one"] = listOf(row("current", count = 200, time = 20), row("half", count = 100, time = 10), row("double", count = 400, time = 30)) }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val final = repo.search(ChapterSourceSearchRequest("Book", "Author", currentBookUrl = "current", loadWordCount = true, filterMode = 2, minimum = 40, maximum = 60), emptyList()).last()
        assertEquals(200, final.referenceWordCount); assertEquals(listOf("current", "half"), final.rows.map { it.id }); assertEquals(3, final.allRows.size)
    }
    @Test fun laterResultFailurePreservesEarlierResultFromSameSourceAndOtherSources() = runTest {
        val first = row("first"); val other = row("other")
        val store = Fake().apply {
            sources = listOf("partial", "other")
            streams["partial"] = flow { emit(first); error("next result has an empty TOC") }
            results["other"] = listOf(other)
        }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler), concurrency = 2)
        val updates = repo.search(ChapterSourceSearchRequest("Book", "Author", loadWordCount = true), emptyList()).toList()
        assertEquals(setOf("first", "other"), updates.last().allRows.map { it.id }.toSet())
        assertEquals(setOf("first", "other"), store.persisted.map { it.id }.toSet())
        assertTrue(updates.any { it.running && it.rows.any { row -> row.id == "first" } })
        assertEquals(2, updates.last().completed); assertEquals(1, store.errors.size)
        assertFalse(updates.last().running)
    }
    @Test fun persistenceAndSourceNameFailuresAreIsolatedAndMeasurementRetainsPreviousRow() = runTest {
        val good = row("good"); val failed = row("write-failed")
        val store = Fake().apply {
            sources = listOf("name-failed", "write-failed", "good")
            nameFailures = setOf("name-failed"); persistFailures = setOf("write-failed")
            results["write-failed"] = listOf(failed); results["good"] = listOf(good)
            measured[failed.id] = row(failed.id, count = 200)
        }
        val repo = DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler), concurrency = 2)
        val result = repo.search(ChapterSourceSearchRequest("Book", "Author"), emptyList()).last()
        assertEquals(listOf("good"), result.rows.map { it.id }); assertEquals(3, result.completed)
        assertFalse(result.running); assertEquals(2, store.errors.size)
        val measured = repo.measure(ChapterSourceSearchRequest("Book", "Author", loadWordCount = true), listOf(failed, good), false).last()
        assertEquals(failed, measured.allRows.first { it.id == failed.id })
        assertEquals(2, measured.completed); assertFalse(measured.running)
        assertEquals(3, store.errors.size); assertTrue(store.persisted.none { it.id == failed.id })
    }
    @Test fun allStoreAccessAndResultPolicyRunOnInjectedIoDispatcher() = runBlocking {
        val main = Thread.currentThread(); val observed = mutableListOf<Thread>()
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val store = Fake().apply { threads = observed; sources = listOf("one"); results["one"] = listOf(row("one")); cached = listOf(row("cached")) }
            val repo = DefaultChapterSourceSearchRepository(store, executor); val request = ChapterSourceSearchRequest("Book", "Author")
            repo.cached(request); repo.project(request, store.cached); repo.search(request, store.cached).toList(); repo.measure(request, store.cached, false).toList()
            assertTrue(observed.isNotEmpty()); assertTrue(observed.all { it !== main })
        } finally { executor.close() }
    }
    private class Fake : ChapterSourceSearchStore {
        var cached = emptyList<ChapterSourceSearchRow>(); var sources = emptyList<String>(); var failing = emptySet<String>(); var gate: CompletableDeferred<Unit>? = null
        val streams = mutableMapOf<String, Flow<ChapterSourceSearchRow>>()
        var nameFailures = emptySet<String>(); var persistFailures = emptySet<String>()
        override fun searchResults(request: ChapterSourceSearchRequest, source: String): Flow<ChapterSourceSearchRow> = streams[source]
            ?: flow { search(request, source).forEach { emit(it) } }
        override suspend fun sourceName(origin: String): String { access(); if (origin in nameFailures) error("name lookup failed"); return origin }
        val results = mutableMapOf<String, List<ChapterSourceSearchRow>>(); val measured = mutableMapOf<String, ChapterSourceSearchRow>(); val scores = mutableMapOf<String, Int>()
        val delays = mutableMapOf<String, Long>(); val persisted = mutableListOf<ChapterSourceSearchRow>(); val errors = mutableListOf<Throwable>(); val measureCalls = mutableListOf<String>()
        var resetRows = emptyList<ChapterSourceSearchRow>(); var active = 0; var maxActive = 0; var threads: MutableList<Thread>? = null
        private fun access() { threads?.add(Thread.currentThread()) }
        override suspend fun cached(request: ChapterSourceSearchRequest): List<ChapterSourceSearchRow> { access(); return cached }
        override suspend fun sources(request: ChapterSourceSearchRequest): ChapterSourceSearchSources { access(); return ChapterSourceSearchSources(sources, "") }
        override suspend fun reset(previous: List<ChapterSourceSearchRow>) { access(); resetRows = previous }
        override suspend fun search(request: ChapterSourceSearchRequest, source: String): List<ChapterSourceSearchRow> {
            access(); active++; maxActive = maxOf(maxActive, active)
            try { gate?.let { withContext(NonCancellable) { it.await() } }; delay(delays[source] ?: 10L); if (source in failing) error("failed"); return results[source].orEmpty() }
            finally { active-- }
        }
        override suspend fun measure(request: ChapterSourceSearchRequest, row: ChapterSourceSearchRow): ChapterSourceSearchRow {
            access(); measureCalls += row.id; if (row.id in failing) error("failed"); return measured[row.id] ?: row
        }
        override suspend fun persist(row: ChapterSourceSearchRow) { access(); if (row.id in persistFailures) error("persist failed"); persisted += row }
        override suspend fun reference(request: ChapterSourceSearchRequest): Int? { access(); return null }
        override fun sourceScore(origin: String): Int { access(); return scores[origin] ?: 0 }
        override fun threadCount(): Int { access(); return 3 }
        override fun log(error: Throwable) { access(); errors += error }
    }
}
