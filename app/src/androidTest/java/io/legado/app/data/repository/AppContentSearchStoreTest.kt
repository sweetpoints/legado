package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.model.book.ContentSearchMatch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.last
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppContentSearchStoreTest {
    @Test fun actualAtomicFilesKeepLargeSearchResultsRejectOldRevisionsAndRestoreBackupAcrossInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val session = UUID.randomUUID().toString()
        val file = File(context.filesDir, "content-search-sessions/$session.json")
        val first = AppContentSearchStore(context); val other = AppContentSearchStore(context)
        try { withContext(Dispatchers.IO) {
            val result = ContentSearchMatch("match", resultText = "x".repeat(1200000), query = "x", chapterIndex = 9, queryIndexInChapter = 20)
            val snapshot = ContentSearchSession("book", query = "x", results = listOf(result), pendingResult = result.id, revision = 10)
            first.create(session, snapshot); other.write(session, snapshot.copy(revision = 9, results = emptyList()))
            assertEquals(snapshot, other.read(session))
            coroutineScope { (11L..20L).map { revision -> async { other.write(session, snapshot.copy(revision = revision)) } }.awaitAll() }
            assertEquals(20L, first.read(session)!!.revision)
            assertTrue(file.renameTo(File(file.path + ".bak")))
            assertEquals(1200000, other.read(session)!!.results.single().resultText.length)
            assertEquals("match", other.read(session)!!.pendingResult)
        } } finally { withContext(Dispatchers.IO) { file.delete(); File(file.path + ".bak").delete() } }
    }
    @Test fun ownedReleaseRemovesOnlyItsLargeFilesAndClosedFenceRejectsLateWritersAndOwnerRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val session = UUID.randomUUID().toString(); val otherSession = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "content-search-sessions")
        val first = AppContentSearchStore(context); val other = AppContentSearchStore(context)
        try { withContext(Dispatchers.IO) {
            val snapshot = ContentSearchSession("book", results = listOf(ContentSearchMatch("large", resultText = "x".repeat(1200000))), revision = 10)
            first.create(session, snapshot); other.create(otherSession, snapshot.copy(bookUrl = "other"))
            val file = File(directory, "$session.json"); assertTrue(file.renameTo(File(file.path + ".bak")))
            File(file.path + ".new").writeText("unfinished large payload")
            first.release(session); assertFalse(file.exists()); assertFalse(File(file.path + ".bak").exists()); assertFalse(File(file.path + ".new").exists())
            assertEquals("other", first.read(otherSession)!!.bookUrl)
            val late = listOf<suspend () -> Unit>(
                { other.write(session, snapshot.copy(revision = 100)) }, { other.create(session, snapshot) }, { other.read(session); Unit })
            late.forEach { block -> try { block(); fail("Expected closed fence") } catch (_: ContentSearchSessionClosedException) { } }
            other.release(session); assertFalse(file.exists())
            first.release(otherSession); assertFalse(File(directory, "$otherSession.json").exists())
            // Ordinary updates cannot create an owner that has never initialized its private session.
            try { first.write(UUID.randomUUID().toString(), snapshot); fail("Expected missing owner") } catch (_: ContentSearchSessionClosedException) { }
        } } finally { withContext(Dispatchers.IO) {
            listOf(session, otherSession).forEach { key -> listOf("json", "json.bak", "json.new", "closed", "closed.bak").forEach { File(directory, "$key.$it").delete() } }
        } }
    }
    @Test fun actualRoomAndBookHelpCacheReadOnlyCachedOnlineChaptersWithoutMutatingChapterMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); val token = UUID.randomUUID().toString()
        val book = Book(bookUrl = "book-$token", origin = "https://source.invalid", name = "Content search $token", author = "Author", durChapterIndex = 3)
        val cached = BookChapter(bookUrl = book.bookUrl, url = "chapter-3", index = 3, title = "Cached title", variable = "metadata")
        val missing = BookChapter(bookUrl = book.bookUrl, url = "chapter-4", index = 4, title = "Missing title")
        try { withContext(Dispatchers.IO) {
            db.bookDao.insert(book); db.bookChapterDao.insert(cached, missing)
            BookHelp.saveText(book, cached, "First needle and second needle.")
            val store = AppContentSearchStore(context, db); val repo = DefaultContentSearchRepository(store)
            val loaded = repo.load(book.bookUrl); assertFalse(loaded.book.local); assertEquals(3, loaded.book.currentChapter)
            assertTrue(cached.getFileName() in loaded.cacheNames); assertFalse(missing.getFileName() in loaded.cacheNames)
            val result = repo.search(loaded.book, "needle") { loaded.cacheNames }.last()
            assertEquals(2, result.results.size); assertTrue(result.results.all { it.chapterIndex == 3 && it.query == "needle" })
            assertEquals(listOf(0, 1), result.results.map { it.resultCountWithinChapter })
            val saved = db.bookChapterDao.getChapter(book.bookUrl, 3)!!
            assertEquals("Cached title", saved.title); assertEquals("metadata", saved.variable)
        } } finally { withContext(Dispatchers.IO) { BookHelp.delContent(book, cached); db.close() } }
    }
}
