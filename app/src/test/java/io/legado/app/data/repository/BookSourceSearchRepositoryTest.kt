package io.legado.app.data.repository

import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceSearchRepositoryTest {
    private fun row(id: String, origin: String = id, count: Int = -1, time: Int = 10, score: Int = 0): ChapterSourceSearchRow {
        val book = SearchBook(bookUrl = id, origin = origin, originName = "Source $origin", name = "Book", author = "Author", coverUrl = "cover-$id",
            intro = "intro-$id", variable = "{\"custom\":1}", chapterWordCount = count, respondTime = time,
            chapterWordCountText = count.takeIf { it >= 0 }?.let { "[3] Title 字数：$it" })
        return ChapterSourceSearchRow(id, origin, book.originName, book.name, book.author, "Latest", book.chapterWordCountText, count, time, 0, score, 0, GSON.toJson(book))
    }
    @Test fun bookResultsDoNotPinCurrentButChapterDefaultStillPinsAndFullMetadataSurvives() = runTest {
        val current = row("current", count = 10, time = 100); val good = row("good", count = 200, time = 10)
        val store = Fake().apply { cached = listOf(current, good) }; val request = ChapterSourceSearchRequest("Book", "Author", currentBookUrl = "current", loadWordCount = true, sortResponseTime = true, filterMode = 1, minimum = 100, maximum = 300)
        val repo = DefaultBookSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val result = repo.cached(request); assertEquals(listOf("good"), result.rows.map { it.id }); assertEquals(store.cached, result.allRows)
        assertEquals("cover-current", GSON.fromJson(result.allRows.first().json, SearchBook::class.java).coverUrl)
        assertEquals(listOf("current", "good"), DefaultChapterSourceSearchRepository(store, StandardTestDispatcher(testScheduler)).cached(request).rows.map { it.id })
    }
    @Test fun unpinnedCurrentMeasuredRowStillSuppliesRelativeReference() = runTest {
        val store = Fake().apply { sources = listOf("all"); results["all"] = listOf(row("current", count = 200), row("half", count = 100), row("double", count = 400)) }
        val request = ChapterSourceSearchRequest("Book", "Author", currentBookUrl = "current", loadWordCount = true, filterMode = 2, minimum = 40, maximum = 60)
        val repo = DefaultBookSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val result = repo.search(request, emptyList()).last(); assertEquals(listOf("half"), result.rows.map { it.id }); assertEquals(200, result.referenceWordCount)
        assertEquals(listOf("half"), repo.project(request, result.allRows).map { it.id })
    }
    @Test fun originOnlyRefreshDeletesCapturedOriginRowsKeepsOtherScoresAndMetadataAndPublishesOneSourceProgress() = runTest {
        val retained = row("retained", "other", score = 1); val previous = row("previous", "edited")
        val store = Fake().apply { results["edited"] = listOf(row("new", "edited")) }
        val repo = DefaultBookSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val updates = repo.search(ChapterSourceSearchRequest("Book", "Author", group = "Group"), listOf(previous, retained), "edited").toList()
        assertEquals(listOf(previous), store.reset); assertEquals(setOf("retained", "new"), updates.last().allRows.map { it.id }.toSet())
        assertEquals(retained, updates.last().allRows.first { it.id == retained.id }); assertEquals("Group", updates.last().effectiveGroup)
        assertEquals(1, updates.last().total); assertEquals(1, updates.last().completed)
        assertEquals(listOf("new"), store.persisted.map { it.id })
    }
    @Test fun failedSingleOriginRetainsUnrelatedRowsWhilePartialSuccessSurvivesLaterFailure() = runTest {
        val retained = row("retained", "other"); val first = row("first", "edited")
        val store = Fake().apply { stream = flow { emit(first); error("later TOC failed") } }
        val repo = DefaultBookSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
        val result = repo.search(ChapterSourceSearchRequest("Book", "Author"), listOf(retained), "edited").last()
        assertEquals(setOf("retained", "first"), result.allRows.map { it.id }.toSet()); assertEquals(listOf(first), store.persisted)
        assertEquals(1, result.completed); assertFalse(result.running); assertEquals(1, store.errors.size)
    }
    @Test fun originRefreshKeepsReferenceFromRetainedCurrentBookWhenFreshSourceIsNotCurrent() = runTest {
        val current = row("current", "other", count = 200); val store = Fake().apply { results["edited"] = listOf(row("half", "edited", count = 100)) }
        val request = ChapterSourceSearchRequest("Book", "Author", currentBookUrl = "current", loadWordCount = true, filterMode = 2, minimum = 40, maximum = 60)
        val result = DefaultBookSourceSearchRepository(store, StandardTestDispatcher(testScheduler)).search(request, listOf(current), "edited").last()
        assertEquals(listOf("half"), result.rows.map { it.id }); assertEquals(200, result.referenceWordCount)
    }
    @Test fun originRefreshQueryExcludingCurrentStillUsesItsReferenceBeforeFilteringTitles() = runTest {
        val current = row("current", "other", count = 200)
        val half = row("half", "edited", count = 100).copy(name = "Half")
        val store = Fake().apply { results["edited"] = listOf(half, row("double", "edited", count = 400).copy(name = "Half double")) }
        val request = ChapterSourceSearchRequest("Book", "Author", query = "Half", currentBookUrl = "current",
            loadWordCount = true, filterMode = 2, minimum = 40, maximum = 60)
        val result = DefaultBookSourceSearchRepository(store, StandardTestDispatcher(testScheduler))
            .search(request, listOf(current), "edited").last()
        assertEquals(listOf("half"), result.rows.map { it.id })
        assertEquals(200, result.referenceWordCount)
        assertEquals(setOf("current", "half", "double"), result.allRows.map { it.id }.toSet())
    }
    private class Fake : BookSourceSearchStore {
        var cached = emptyList<ChapterSourceSearchRow>(); var sources = emptyList<String>(); var stream: Flow<ChapterSourceSearchRow>? = null
        var reset = emptyList<ChapterSourceSearchRow>(); val results = mutableMapOf<String, List<ChapterSourceSearchRow>>()
        val persisted = mutableListOf<ChapterSourceSearchRow>(); val errors = mutableListOf<Throwable>()
        override suspend fun sourceExists(origin: String) = true
        override suspend fun cached(request: ChapterSourceSearchRequest) = cached
        override suspend fun sources(request: ChapterSourceSearchRequest) = ChapterSourceSearchSources(sources, request.group)
        override suspend fun reset(previous: List<ChapterSourceSearchRow>) { reset = previous }
        override suspend fun search(request: ChapterSourceSearchRequest, source: String) = results[source].orEmpty()
        override fun searchResults(request: ChapterSourceSearchRequest, source: String) = stream ?: flow { search(request, source).forEach { emit(it) } }
        override suspend fun measure(request: ChapterSourceSearchRequest, row: ChapterSourceSearchRow) = row
        override suspend fun persist(row: ChapterSourceSearchRow) { persisted += row }
        override suspend fun reference(request: ChapterSourceSearchRequest): Int? = null
        override fun sourceScore(origin: String) = 0
        override fun threadCount() = 2
        override fun log(error: Throwable) { errors += error }
    }
}
