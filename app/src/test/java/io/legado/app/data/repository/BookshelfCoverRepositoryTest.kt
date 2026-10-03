package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfCoverRepositoryTest {
    private class Store : BookshelfCoverStore {
        val rows = linkedMapOf("a" to Book(bookUrl = "a", name = "A", coverUrl = "https://cover/a"), "b" to Book(bookUrl = "b", name = "B", coverUrl = "https://cover/b"),
            "local-cover" to Book(bookUrl = "local-cover", customCoverUrl = "/local/image"))
        var gate: CompletableDeferred<Unit>? = null; var lateFailure = false; var editDuringDownload = false
        var failures = 0; val downloads = mutableListOf<String>()
        override fun read(id: String) = rows[id]?.copy()
        override suspend fun download(book: Book, url: String): String {
            downloads += url
            if (editDuringDownload) rows[book.bookUrl] = rows.getValue(book.bookUrl).copy(customCoverUrl = "https://new-custom")
            if (book.bookUrl == "b") error("download failed")
            gate?.let { withContext(NonCancellable) { it.await(); if (lateFailure) error("late failure") } }
            return "synthetic-persistent-path"
        }
        override fun installIfUnchanged(book: Book, path: String): Boolean {
            val latest = rows[book.bookUrl] ?: return false
            if (latest.origin != book.origin || latest.coverUrl != book.coverUrl || latest.customCoverUrl != book.customCoverUrl || latest.persistedCoverUrl != book.persistedCoverUrl) return false
            rows[book.bookUrl] = latest.copy(persistedCoverUrl = path); return true
        }
        override fun restoreNetworkIfUnchanged(book: Book): Boolean {
            val latest = rows[book.bookUrl] ?: return false
            if (latest.persistedCoverUrl != book.persistedCoverUrl) return false
            rows[book.bookUrl] = latest.copy(persistedCoverUrl = null); return true
        }
        override fun restoreSourceIfUnchanged(book: Book): Boolean {
            val latest = rows[book.bookUrl] ?: return false
            if (latest.customCoverUrl != book.customCoverUrl || latest.persistedCoverUrl != book.persistedCoverUrl) return false
            rows[book.bookUrl] = latest.copy(customCoverUrl = null, persistedCoverUrl = null); return true
        }
        override fun failure(book: Book, error: Exception) { failures++ }
    }
    @Test fun persistenceCountsSuccessMissingLocalAndFailureAndRetainsOrderedProgress() = runTest {
        val store = Store(); val repo = DefaultBookshelfCoverRepository(store, StandardTestDispatcher(testScheduler))
        val events = repo.run(listOf("a", "missing", "local-cover", "b", "a"), ShelfCoverAction.PersistNetwork).toList()
        assertEquals(listOf(1, 3, 4), events.filterIsInstance<ShelfCoverEvent.Progress>().map { it.position })
        assertEquals(ShelfCoverSummary(1, 2, 1), (events.last() as ShelfCoverEvent.Completed).summary)
        assertEquals(listOf("https://cover/a", "https://cover/b"), store.downloads); assertEquals(1, store.failures)
    }
    @Test fun coverEditedDuringDownloadCannotBeOverwrittenByLateCompletion() = runTest {
        val store = Store().apply { editDuringDownload = true }; val repo = DefaultBookshelfCoverRepository(store, StandardTestDispatcher(testScheduler))
        val result = repo.run(listOf("a"), ShelfCoverAction.PersistNetwork).toList().last() as ShelfCoverEvent.Completed
        assertEquals(ShelfCoverSummary(0, 1, 0), result.summary); assertEquals("https://new-custom", store.rows.getValue("a").customCoverUrl); assertNull(store.rows.getValue("a").persistedCoverUrl)
    }
    @Test fun networkRestoreKeepsCustomCoverWhileSourceRestoreClearsBothOverrides() = runTest {
        val store = Store(); store.rows["a"] = store.rows.getValue("a").copy(customCoverUrl = "https://custom", persistedCoverUrl = "persistent", durChapterIndex = 11)
        val repo = DefaultBookshelfCoverRepository(store, StandardTestDispatcher(testScheduler))
        repo.run(listOf("a"), ShelfCoverAction.RestoreNetwork).toList(); assertNull(store.rows.getValue("a").persistedCoverUrl); assertEquals("https://custom", store.rows.getValue("a").customCoverUrl)
        repo.run(listOf("a"), ShelfCoverAction.RestoreSource).toList(); assertNull(store.rows.getValue("a").customCoverUrl); assertEquals(11, store.rows.getValue("a").durChapterIndex)
    }
    @Test fun canceledNonCooperativeDownloadCannotPublishErrorCommitOrCompletion() = runTest {
        val gate = CompletableDeferred<Unit>(); val store = Store().apply { this.gate = gate; lateFailure = true }; val events = mutableListOf<ShelfCoverEvent>()
        val job = launch { DefaultBookshelfCoverRepository(store, StandardTestDispatcher(testScheduler)).run(listOf("a"), ShelfCoverAction.PersistNetwork).toList(events) }
        runCurrent(); assertEquals(1, events.size); job.cancel(); gate.complete(Unit); job.join()
        assertEquals(0, store.failures); assertNull(store.rows.getValue("a").persistedCoverUrl); assertTrue(events.none { it is ShelfCoverEvent.Completed })
    }
}
