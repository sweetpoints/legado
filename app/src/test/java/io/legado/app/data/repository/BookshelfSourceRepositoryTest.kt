package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.*
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfSourceRepositoryTest {
    private class Store : BookshelfSourceStore {
        val selected = BookSource(bookSourceUrl = "new-source", bookSourceName = "New")
        val rows = linkedMapOf("a" to Book(bookUrl = "a", name = "A", author = "one", origin = "old-source"),
            "b" to Book(bookUrl = "b", name = "B", author = "two", origin = "old-source"),
            "local" to Book(bookUrl = "local", name = "Local", origin = BookType.localTag, type = BookType.text or BookType.local),
            "same" to Book(bookUrl = "same", name = "Same", origin = "new-source"))
        var missingSource = false; var failInformation = false; var failChapters = false; var gate: CompletableDeferred<Unit>? = null; var lateFailure = false
        val calls = mutableListOf<String>(); val failures = mutableListOf<ShelfSourceStage>(); var committedOriginal: Book? = null
        override fun source(id: String) = selected.takeUnless { missingSource }
        override fun read(id: String) = rows[id]?.copy()
        override fun delayMillis() = 1000L
        override suspend fun search(source: BookSource, book: Book): Book? {
            calls += "search:${book.bookUrl}"
            gate?.let { withContext(NonCancellable) { it.await(); if (lateFailure) error("late network error") } }
            if (book.bookUrl == "b") error("search failure")
            return Book(bookUrl = "replacement", name = book.name, author = book.author, origin = source.bookSourceUrl)
        }
        override suspend fun information(source: BookSource, book: Book) { calls += "information"; if (failInformation) error("info failure"); book.tocUrl = "toc" }
        override suspend fun chapters(source: BookSource, book: Book): List<BookChapter> { calls += "chapters"; if (failChapters) error("toc failure"); return emptyList() }
        override fun commitIfUnchanged(original: Book, replacement: Book, chapters: List<BookChapter>): Boolean {
            calls += "commit"; val latest = rows[original.bookUrl] ?: return false
            if (latest.origin != original.origin || latest.name != original.name || latest.author != original.author) return false
            committedOriginal = latest.copy(); rows.remove(original.bookUrl); rows[replacement.bookUrl] = replacement.copy(group = latest.group, customCoverUrl = latest.customCoverUrl, durChapterIndex = latest.durChapterIndex); return true
        }
        override fun failure(stage: ShelfSourceStage, error: Exception) { failures += stage }
    }
    @Test fun pipelineSkipsLocalSameAndDeletedRowsWhileSearchFailureCannotLoseOtherSuccess() = runTest {
        val store = Store(); val events = DefaultBookshelfSourceRepository(store, StandardTestDispatcher(testScheduler)).change(listOf("local", "same", "gone", "a", "b", "a"), "new-source").toList()
        assertEquals(listOf(1, 2, 3, 4, 5), events.filterIsInstance<ShelfSourceEvent.Progress>().map { it.position })
        assertEquals(ShelfSourceEvent.Completed(1, 3, 1), events.last()); assertEquals(listOf(ShelfSourceStage.Search), store.failures)
        assertEquals(listOf("search:a", "information", "chapters", "commit", "search:b"), store.calls)
    }
    @Test fun latestMetadataIsPassedToTheCommitAfterNetworkWorkAndDeletedOriginalCannotResurrect() = runTest {
        val gate = CompletableDeferred<Unit>(); val store = Store().apply { this.gate = gate }; val events = mutableListOf<ShelfSourceEvent>()
        val job = launch { DefaultBookshelfSourceRepository(store, StandardTestDispatcher(testScheduler)).change(listOf("a"), "new-source").toList(events) }
        runCurrent(); store.rows["a"] = store.rows.getValue("a").copy(group = 8, customCoverUrl = "latest-custom", durChapterIndex = 29)
        gate.complete(Unit); job.join(); assertEquals(29, store.committedOriginal!!.durChapterIndex); assertEquals(8L, store.rows.getValue("replacement").group); assertEquals("latest-custom", store.rows.getValue("replacement").customCoverUrl)
        val deleted = Store(); deleted.rows.remove("a"); assertEquals(ShelfSourceEvent.Completed(0, 1, 0), DefaultBookshelfSourceRepository(deleted, StandardTestDispatcher(testScheduler)).change(listOf("a"), "new-source").toList().last())
    }
    @Test fun changedOriginDuringNetworkIsSkippedRatherThanOverwritten() = runTest {
        val gate = CompletableDeferred<Unit>(); val store = Store().apply { this.gate = gate }; val events = mutableListOf<ShelfSourceEvent>()
        val job = launch { DefaultBookshelfSourceRepository(store, StandardTestDispatcher(testScheduler)).change(listOf("a"), "new-source").toList(events) }
        runCurrent(); store.rows["a"] = store.rows.getValue("a").copy(origin = "another-source"); gate.complete(Unit); job.join()
        assertEquals(ShelfSourceEvent.Completed(0, 1, 0), events.last()); assertEquals("another-source", store.rows.getValue("a").origin); assertNull(store.committedOriginal)
    }
    @Test fun informationAndChapterFailuresDoNotCommitAndKeepTheirOriginalPerSourceLoggingStages() = runTest {
        listOf(ShelfSourceStage.Information, ShelfSourceStage.Chapters).forEach { stage ->
            val store = Store().apply { failInformation = stage == ShelfSourceStage.Information; failChapters = stage == ShelfSourceStage.Chapters }
            val result = DefaultBookshelfSourceRepository(store, StandardTestDispatcher(testScheduler)).change(listOf("a"), "new-source").toList().last()
            assertEquals(ShelfSourceEvent.Completed(0, 0, 1), result); assertEquals(listOf(stage), store.failures); assertFalse(store.calls.contains("commit")); assertTrue(store.rows.containsKey("a"))
        }
    }
    @Test fun canceledNonCooperativeSearchNeverLogsLateErrorOrCommitsOrFinishes() = runTest {
        val gate = CompletableDeferred<Unit>(); val store = Store().apply { this.gate = gate; lateFailure = true }; val events = mutableListOf<ShelfSourceEvent>()
        val job = launch { DefaultBookshelfSourceRepository(store, StandardTestDispatcher(testScheduler)).change(listOf("a"), "new-source").toList(events) }
        runCurrent(); job.cancel(); gate.complete(Unit); job.join()
        assertTrue(store.failures.isEmpty()); assertFalse(store.calls.contains("commit")); assertTrue(events.none { it is ShelfSourceEvent.Completed })
    }
}
