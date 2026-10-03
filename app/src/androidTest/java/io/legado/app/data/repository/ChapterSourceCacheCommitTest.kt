package io.legado.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.model.book.ChapterSourceCacheDigest
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ChapterSourceCacheCommitTest {
    @Test
    fun realConcurrentReplacementRejectsStaleJournalAndPreservesNewerBodyAndMetadata() =
        runBlocking {
            val token = UUID.randomUUID().toString()
            val book =
                Book(
                    bookUrl = "https://$token.invalid/book",
                    origin = "https://$token.invalid",
                    name = "Cache-$token",
                    author = "Author",
                )
            val original =
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "$token/chapter",
                    title = "Original",
                    index = 0,
                )
            try {
                withContext(Dispatchers.IO) {
                    appDb.bookDao.insert(book)
                    appDb.bookChapterDao.insert(original)
                    BookHelp.saveText(book, original, "Original body", true)
                    val baselineRead = CompletableDeferred<String>()
                    val newerCommitted = CompletableDeferred<Unit>()
                    coroutineScope {
                        val journal =
                            async(Dispatchers.IO) {
                                val hash =
                                    ChapterSourceCacheDigest.of(BookHelp.getContent(book, original))
                                baselineRead.complete(hash)
                                newerCommitted.await()
                                BookHelp.saveTextIfUnchanged(
                                    book,
                                    original,
                                    "Old pending body",
                                    hash,
                                    true,
                                )
                            }
                        val newer =
                            async(Dispatchers.IO) {
                                baselineRead.await()
                                BookHelp.saveText(
                                    book,
                                    original.copy(title = "New metadata", imgUrl = "new-image"),
                                    "Newer body",
                                    true,
                                )
                                newerCommitted.complete(Unit)
                            }
                        newer.await()
                        assertFalse(journal.await())
                    }
                    assertEquals("Newer body", BookHelp.getContent(book, original))
                    val stored = appDb.bookChapterDao.getChapter(book.bookUrl, 0)!!
                    assertEquals("New metadata", stored.title)
                    assertEquals("new-image", stored.imgUrl)
                }
            } finally {
                withContext(Dispatchers.IO) {
                    BookHelp.clearCache(book)
                    appDb.bookDao.delete(book)
                }
            }
        }

    @Test
    fun realAlreadyWrittenBodyOnlyCompletesReceiptWithoutAnotherBodyOrMetadataWrite() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val session = UUID.randomUUID().toString()
            val book =
                Book(
                    bookUrl = "https://$session.invalid/book",
                    origin = "https://$session.invalid",
                    name = "Cache-$session",
                    author = "Author",
                )
            val original =
                BookChapter(
                    bookUrl = book.bookUrl,
                    url = "$session/chapter",
                    title = "Old metadata",
                    index = 0,
                )
            val directory = File(context.filesDir, "chapter-source-sessions/$session")
            try {
                withContext(Dispatchers.IO) {
                    appDb.bookDao.insert(book)
                    appDb.bookChapterDao.insert(original)
                    BookHelp.saveText(
                        book,
                        original.copy(title = "Committed metadata", imgUrl = "committed-image"),
                        "Pending body",
                        true,
                    )
                    val projected =
                        ChapterSourceChapter(
                            "chapter",
                            0,
                            original.title,
                            false,
                            null,
                            GSON.toJson(original),
                        )
                    val store = AppChapterSourceContentStore(context)
                    store.writeReceipt(
                        session,
                        ChapterSourceReceipt(
                            "receipt",
                            ChapterSourceReceiptKind.Cache,
                            body = "Pending body",
                            chapterIndex = 0,
                            previousBodyHash = ChapterSourceCacheDigest.of("Previous body"),
                        ),
                    )
                    val token = BookHelp.contentSaveToken(book, original)
                    val repo = DefaultChapterSourceContentRepository(store)
                    assertTrue(
                        repo.recoverCache(session, GSON.toJson(book), listOf(projected))!!.committed
                    )
                    assertEquals(token.version, BookHelp.contentSaveToken(book, original).version)
                    assertEquals(
                        "Committed metadata",
                        appDb.bookChapterDao.getChapter(book.bookUrl, 0)!!.title,
                    )
                    assertEquals(
                        "committed-image",
                        appDb.bookChapterDao.getChapter(book.bookUrl, 0)!!.imgUrl,
                    )
                    repo.consume(session, "receipt")
                    assertNull(repo.recoverCache(session, GSON.toJson(book), listOf(projected)))
                    assertNotEquals(
                        ChapterSourceCacheDigest.of(null),
                        ChapterSourceCacheDigest.of(""),
                    )
                }
            } finally {
                withContext(Dispatchers.IO) {
                    BookHelp.clearCache(book)
                    appDb.bookDao.delete(book)
                    directory.deleteRecursively()
                }
            }
        }
}
