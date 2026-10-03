package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BookCacheRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var directory: File
    @Before fun before() { database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); directory = File(context.cacheDir, "cache-test-${UUID.randomUUID()}") }
    @After fun after() { database.close(); directory.deleteRecursively() }
    private fun repo(sort: Int = 0, files: Set<String> = emptySet()) = RoomBookCacheRepository(context, database, { sort }, { files }, directory)
    @Test fun actualRoomFlowFiltersAudioAndKeepsLocalRowsWithAllFiveExistingSortModes() = runBlocking {
        val a = Book(bookUrl = "a", name = "A", order = 2, latestChapterTime = 10, durChapterTime = 30)
        val b = Book(bookUrl = "b", name = "B", order = 1, latestChapterTime = 40, durChapterTime = 20, type = BookType.text or BookType.local)
        withContext(Dispatchers.IO) { database.bookDao.insert(a, b, Book(bookUrl = "audio", name = "Audio", type = BookType.audio)) }
        val expectations = listOf(listOf("a", "b"), listOf("b", "a"), listOf("a", "b"), listOf("b", "a"), listOf("b", "a"))
        for (mode in 0..4) assertEquals(expectations[mode], repo(mode).books(-1).first().map { it.key })
        assertTrue(repo().books(-1).first().single { it.key == "b" }.local)
    }
    @Test fun actualGroupFlowAndBookMetadataChangesProduceImmutableNewSnapshots() = runBlocking {
        withContext(Dispatchers.IO) { database.bookGroupDao.insert(BookGroup(1, "Group")); database.bookDao.insert(Book(bookUrl = "a", name = "Old", group = 1)) }
        val repository = repo(); val first = repository.books(1).first().single(); val group = repository.groups().first().single()
        withContext(Dispatchers.IO) { database.bookDao.insert(Book(bookUrl = "a", name = "New", group = 1)); database.bookGroupDao.insert(BookGroup(1, "Changed")) }
        assertEquals("Old", first.name); assertEquals("New", repository.books(1).first().single().name); assertEquals("Group", group.name); assertEquals("Changed", repository.groups().first().single().name)
    }
    @Test fun scannerCountsActualFileNamesAndVolumeWithoutMutatingDatabaseTotalAndHandlesMissingLocal() = runBlocking {
        val book = Book(bookUrl = "a", name = "Book", totalChapterNum = 99)
        val cached = BookChapter(url = "cached", bookUrl = "a", title = "Cached", index = 0)
        val missing = BookChapter(url = "missing", bookUrl = "a", title = "Missing", index = 1)
        val volume = BookChapter(url = "volume", bookUrl = "a", title = "Volume", index = 2, isVolume = true)
        withContext(Dispatchers.IO) { database.bookDao.insert(book, Book(bookUrl = "local", name = "Local", totalChapterNum = 7, type = BookType.text or BookType.local)); database.bookChapterDao.insert(cached, missing, volume) }
        val scan = repo(files = setOf(cached.getFileName())).scan("a")!!
        assertEquals(setOf("cached", "volume"), scan.chapters); assertEquals(3, scan.total)
        withContext(Dispatchers.IO) { assertEquals(99, database.bookDao.getBook("a")!!.totalChapterNum) }
        assertEquals(BookCacheScan(emptySet(), 99), repo().scan("a")); assertEquals(BookCacheScan(emptySet(), 7), repo().scan("local")); assertNull(repo().scan("gone"))
    }
    @Test fun diskSelectionSurvivesRepositoryRecreationAndRejectsPathTraversalAndCleansOnlyOwnedTicket() = runBlocking {
        val first = repo(); val ticket = first.stage(listOf("a", "b", "a")); val other = first.stage(listOf("other"))
        assertEquals(listOf("a", "b"), repo().staged(ticket)); assertTrue(directory.listFiles()!!.all { it.extension == "json" })
        first.release(ticket); assertFalse(File(directory, "$ticket.json").exists()); assertEquals(listOf("other"), first.staged(other)); first.release(other)
        assertTrue(runCatching { first.staged("../outside") }.isFailure)
    }
    @Test fun fullLargeSectionDraftIsDurableAndOlderCompletionCannotOverwriteNewerRevision() = runBlocking {
        val repository = repo(); val ticket = repository.stage(listOf("a"))
        val text = "x".repeat(400_000)
        val newer = BookCacheSectionDraft("content://folder", false, "3", text, text, 7)
        repository.writeSection(ticket, newer)
        repository.writeSection(ticket, newer.copy(name = "old", scope = "old", revision = 6))
        assertEquals(newer, repo().readSection(ticket)); assertEquals(listOf("a"), repo().staged(ticket))
        repository.release(ticket); assertFalse(File(directory, "$ticket.json").exists())
    }
    @Test fun recreatedRepositoryReleaseSerializesWithInFlightWriteAndLateFlushCannotResurrectTicket() = runBlocking {
        val started = CountDownLatch(1); val finish = CountDownLatch(1)
        val first = RoomBookCacheRepository(context, database, { 0 }, { emptySet() }, directory) {
            started.countDown(); check(finish.await(5, TimeUnit.SECONDS))
        }
        val ticket = first.stage(listOf("a"))
        val writing = async(Dispatchers.IO) { first.writeSection(ticket, BookCacheSectionDraft("path", false, "1", "1", "name", 1)) }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        val releasing = async(Dispatchers.IO) { repo().release(ticket) }
        try { delay(50); assertFalse(releasing.isCompleted) } finally { finish.countDown() }
        writing.await(); releasing.await(); assertFalse(File(directory, "$ticket.json").exists())
        assertTrue(runCatching { repo().writeSection(ticket, BookCacheSectionDraft("path", false, "1", "1", "late", 2)) }.isFailure)
        assertFalse(File(directory, "$ticket.json").exists())
    }
    @Test fun deltaPreferenceWriteCannotRevertAnotherHostsUnrelatedSettings() = runBlocking {
        val repository = repo(); val original = repository.preferences()
        try {
            val stale = original.copy(type = 1)
            AppConfig.exportCharset = "GBK"
            repository.preferences(stale, setOf(BookCachePreference.Type))
            assertEquals("GBK", AppConfig.exportCharset); assertEquals(1, AppConfig.exportType)
            repository.preferences(stale.copy(custom = true), setOf(BookCachePreference.Custom))
            assertTrue(repository.preferences().customEpub); assertEquals("GBK", repository.preferences().charset)
        } finally { repository.preferences(original, BookCachePreference.entries.toSet()) }
    }
    @Test fun namingUsesActualJsBindingsAndInvalidRuleDoesNotReplacePreference() = runBlocking {
        withContext(Dispatchers.IO) { database.bookDao.insert(Book(bookUrl = "a", name = "Novel", author = "Writer")) }
        val repository = repo()
        assertFalse(repository.validEpisodeName("")); assertFalse(repository.validEpisodeName("throw new Error('bad')")); assertNull(repository.episodeName("a", "throw new Error('bad')"))
        assertTrue(repository.validEpisodeName("name + '-' + author + '-' + epubIndex"))
        val result = repository.episodeName("a", "name + '-' + author + '-' + epubIndex")!!
        assertTrue(result.contains("Novel")); assertTrue(result.contains("Writer")); assertTrue(result.endsWith(".epub")); assertNull(repository.episodeName("missing", "name"))
    }
}
