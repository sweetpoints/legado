package io.legado.app.ui.book.read

import android.content.Context
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ContentEditorRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: AppDatabase
    private lateinit var repository: BookContentEditorRepository
    private lateinit var drafts: File
    private val target = ContentEditorTarget("content-editor-test", 3, 22)
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        drafts = File(context.cacheDir, "content-drafts-${System.nanoTime()}")
        repository = BookContentEditorRepository(context, database, drafts)
    }
    @After fun close() { database.close(); drafts.deleteRecursively() }
    @Test fun largeUnicodeDraftRoundTripsOutsideBundleWithExactTargetAndDirtyFlag() = runBlocking {
        val text = "正文\n<img src=\"图像\">".repeat(100000)
        val draft = ContentEditorDraft(target, text, true, 123)
        repository.writeDraft("large", draft)
        assertEquals(draft, repository.readDraft("large")); assertTrue(File(drafts, "large.json").length() > 1000000)
        repository.deleteDraft("large"); assertNull(repository.readDraft("large"))
    }
    @Test fun staleWriterCannotReplaceMoreRecentDraft() = runBlocking {
        repository.writeDraft("same", ContentEditorDraft(target, "latest", true, 20))
        repository.writeDraft("same", ContentEditorDraft(target, "stale", false, 19))
        assertEquals("latest", repository.readDraft("same")!!.text)
    }
    @Test fun concurrentRepositoryInstancesKeepHighestCommittedRevision() = runBlocking {
        val other = BookContentEditorRepository(context, database, drafts)
        coroutineScope { (1L..20L).map { revision -> async { (if (revision % 2 == 0L) repository else other).writeDraft("shared", ContentEditorDraft(target, "revision $revision", true, revision)) } }.awaitAll() }
        assertEquals(20L, repository.readDraft("shared")!!.revision)
    }
    @Test fun atomicBackupRecoveryDoesNotFallBackToOriginalChapter() = runBlocking {
        val draft = ContentEditorDraft(target, "checkpoint", true, 8)
        repository.writeDraft("recover", draft)
        val file = File(drafts, "recover.json"); val atomic = AtomicFile(file)
        val incomplete = atomic.startWrite(); incomplete.write("partial".toByteArray()); incomplete.close()
        assertEquals(draft, repository.readDraft("recover"))
    }
    @Test fun corruptedDraftReportsFailureRatherThanSilentlyDiscardingEdits() = runBlocking {
        drafts.mkdirs(); File(drafts, "broken.json").writeText("{broken")
        assertTrue(runCatching { repository.readDraft("broken") }.isFailure)
        assertTrue(runCatching { repository.readDraft("../outside") }.isFailure)
    }
    @Test fun titleUpdatePreservesCurrentChapterMetadataAndMissingRowCannotBeRecreated() = runBlocking {
        val chapter = BookChapter(bookUrl = target.bookUrl, index = target.chapterIndex, title = "old", url = "chapter-url")
        database.bookDao.insert(Book(bookUrl = target.bookUrl)); database.bookChapterDao.insert(chapter)
        assertEquals("old", repository.title(target))
        val latest = chapter.copy(tag = "fresh-metadata"); database.bookChapterDao.update(latest)
        repository.saveTitle(target, "new")
        assertEquals("new", database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex)!!.title)
        assertEquals("fresh-metadata", database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex)!!.tag)
        database.bookChapterDao.delByBook(target.bookUrl)
        assertTrue(runCatching { repository.saveTitle(target, "resurrected") }.isFailure)
        assertNull(database.bookChapterDao.getChapter(target.bookUrl, target.chapterIndex))
    }
    @Test fun bodySaveRejectsMissingFixedBookOrChapter() = runBlocking {
        assertTrue(runCatching { repository.save(target, "body") }.isFailure)
        database.bookDao.insert(Book(bookUrl = target.bookUrl, name = "book"))
        assertTrue(runCatching { repository.save(target, "body") }.isFailure)
    }
}
