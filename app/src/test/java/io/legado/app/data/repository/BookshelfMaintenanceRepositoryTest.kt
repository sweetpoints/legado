package io.legado.app.data.repository

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfMaintenanceRepositoryTest {
    private class Store : BookshelfMaintenanceStore {
        val rows = linkedMapOf("a" to Book(bookUrl = "a", name = "fresh", origin = "https://source", durChapterIndex = 27),
            "local" to Book(bookUrl = "local", name = "local", origin = BookType.localTag, type = BookType.text or BookType.local),
            "disabled" to Book(bookUrl = "disabled", name = "disabled", origin = "https://source", canUpdate = false))
        val events = mutableListOf<String>(); var transactional = false; var cron: String? = null
        val callers = mutableListOf<Thread>()
        override fun read(id: String): Book? { callers += Thread.currentThread(); return rows[id] }
        override fun transaction(block: () -> Unit) { transactional = true; try { block() } finally { transactional = false } }
        override fun snapshot(book: Book) { check(transactional); events += "snapshot:${book.bookUrl}:${book.durChapterIndex}" }
        override fun delete(books: List<Book>) { check(transactional); books.forEach { rows.remove(it.bookUrl); events += "delete:${it.bookUrl}" } }
        override fun deleteResources(book: Book, original: Boolean) { check(!transactional); check(book.bookUrl !in rows); events += "resources:${book.bookUrl}:$original" }
        override fun clearCache(book: Book) { events += "cache:${book.bookUrl}" }
        override fun exportSources() = File("synthetic-only-export.json").also { callers += Thread.currentThread(); events += "export" }
        override fun createTasks(books: List<Book>, cron: String): Int { this.cron = cron; events += books.joinToString(",") { it.bookUrl }; return books.size }
    }
    @Test fun confirmedDeleteReadsFreshRecordsSnapshotsAllBeforeDeletionAndKeepsOriginalFlag() = runTest {
        val store = Store(); val repo = DefaultBookshelfMaintenanceRepository(store, StandardTestDispatcher(testScheduler))
        assertEquals(2, repo.delete(listOf("a", "missing", "a", "local"), true))
        assertEquals(listOf("snapshot:a:27", "snapshot:local:0", "delete:a", "delete:local", "resources:a:true", "resources:local:true"), store.events)
        assertEquals(listOf("disabled"), store.rows.keys.toList())
    }
    @Test fun cacheOnlyUsesCurrentDistinctRowsAndNeverDeletesBookRecords() = runTest {
        val store = Store(); val repo = DefaultBookshelfMaintenanceRepository(store, StandardTestDispatcher(testScheduler))
        assertEquals(1, repo.clearCache(listOf("a", "gone", "a"))); assertEquals(listOf("cache:a"), store.events); assertEquals(3, store.rows.size)
    }
    @Test fun updateAndTaskRequestsExcludeDeletedLocalAndUpdateDisabledRows() = runTest {
        val store = Store(); val repo = DefaultBookshelfMaintenanceRepository(store, StandardTestDispatcher(testScheduler))
        val ids = listOf("a", "gone", "local", "disabled", "a")
        val candidates = repo.updateCandidates(ids); assertEquals(listOf("a"), candidates.map { it.bookUrl })
        candidates.single().name = "changed detached result"; assertEquals("fresh", store.rows.getValue("a").name)
        assertEquals(1, repo.createTasks(ids, " 0 0 * * * ")); assertEquals("0 0 * * *", store.cron); assertEquals(listOf("a"), store.events)
    }
    @Test fun invalidScheduleCannotStartTaskMutation() = runTest {
        val store = Store(); val repo = DefaultBookshelfMaintenanceRepository(store, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { repo.createTasks(listOf("a"), "invalid") }.isFailure); assertTrue(store.events.isEmpty()); assertNull(store.cron)
    }
    @Test fun allMaintenanceDataAndExportPreparationRunOnIoWithoutTouchingRealFiles() = runBlocking {
        val caller = Thread.currentThread(); val store = Store(); val repo = DefaultBookshelfMaintenanceRepository(store)
        assertEquals("synthetic-only-export.json", repo.exportSources().name); repo.updateCandidates(listOf("a"))
        assertTrue(store.callers.isNotEmpty()); assertTrue(store.callers.all { it !== caller }); assertEquals(listOf("export"), store.events)
    }
    @Test fun canceledIoExportCleansOnlyItsUnreturnedPreparedFile() = runBlocking {
        val directory = Files.createTempDirectory("synthetic-shelf-export").toFile()
        val prepared = File(directory, "owned.json"); val other = File(directory, "other.json").apply { writeText("independent export") }
        val started = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val store = object : BookshelfMaintenanceStore by Store() {
            override fun exportSources(): File {
                prepared.writeText("synthetic source data"); started.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)); return prepared
            }
        }
        try {
            val job = launch { DefaultBookshelfMaintenanceRepository(store).exportSources() }
            started.await(); job.cancel(); release.countDown(); job.join()
            assertFalse(prepared.exists()); assertTrue(other.exists()); assertEquals("independent export", other.readText())
        } finally { release.countDown(); directory.deleteRecursively() }
    }

}
