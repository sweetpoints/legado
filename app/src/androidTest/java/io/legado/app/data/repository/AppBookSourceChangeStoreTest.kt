package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppBookSourceChangeStoreTest {
    @Test
    fun actualFilesPreserveLargeMetadataRevisionAndConsumedThenAcknowledgedFlagsAcrossInstancesAndAtomicBackup() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val session = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "book-source-sessions/$session")
            val store = AppBookSourceChangeStore(context)
            val other = AppBookSourceChangeStore(context)
            try {
                withContext(Dispatchers.IO) {
                    val snapshot =
                        BookSourceChangeSession(
                            ChapterSourceSearchRequest(
                                "Book",
                                "Author",
                                originalBookJson = "x".repeat(1200000),
                            ),
                            revision = 12,
                        )
                    store.write(session, snapshot)
                    other.write(session, snapshot.copy(revision = 11))
                    assertEquals(snapshot, other.read(session))
                    coroutineScope {
                        (13L..20L)
                            .map { revision ->
                                async { store.write(session, snapshot.copy(revision = revision)) }
                            }
                            .awaitAll()
                    }
                    assertEquals(20L, other.read(session)!!.revision)
                    val state = File(directory, "state.json")
                    assertTrue(state.renameTo(File(state.path + ".bak")))
                    assertEquals(20L, store.read(session)!!.revision)
                    val receipt =
                        BookSourceChangeReceipt(
                            "receipt",
                            "y".repeat(1200000),
                            "source",
                            emptyList(),
                        )
                    store.writeReceipt(session, receipt)
                    other.writeReceipt(session, receipt.copy(consumed = true))
                    store.writeReceipt(session, receipt.copy(acknowledged = true))
                    other.writeReceipt(session, receipt)
                    val restored = store.receipt(session, receipt.key)!!
                    assertTrue(restored.consumed)
                    assertTrue(restored.acknowledged)
                    assertEquals(receipt.bookJson, restored.bookJson)
                    val result = File(directory, "receipt.json")
                    assertTrue(result.renameTo(File(result.path + ".bak")))
                    assertEquals(restored, other.receipt(session, receipt.key))
                }
            } finally {
                withContext(Dispatchers.IO) { directory.deleteRecursively() }
            }
        }

    @Test
    fun actualRoomAndCachedDirectoryPreparationKeepsAllBookSourceChapterFieldsAndOperationsKeepUnrelatedSourceFields() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                withContext(Dispatchers.IO) {
                    val token = UUID.randomUUID().toString()
                    val source =
                        BookSource(
                            bookSourceUrl = "https://$token.invalid",
                            bookSourceName = "Source",
                            bookSourceGroup = "Group",
                            customOrder = 8,
                            bookSourceComment = "Comment",
                        )
                    val raw =
                        SearchBook(
                            bookUrl = "book-$token",
                            origin = source.bookSourceUrl,
                            name = "Book",
                            author = "Author",
                            intro = "Intro",
                            variable = "{\"custom\":true}",
                        )
                    db.bookSourceDao.insert(source)
                    db.searchBookDao.insert(raw)
                    val search = AppChapterSourceSearchStore(db)
                    val row = search.cached(ChapterSourceSearchRequest("Book", "Author")).single()
                    val book = search.targetBook(row)
                    val chapter =
                        BookChapter(
                            url = "chapter-$token",
                            bookUrl = book.bookUrl,
                            index = 0,
                            title = "Title",
                            tag = "Tag",
                            isVip = true,
                            variable = "chapter-variable",
                            imgUrl = "image",
                        )
                    search.rememberToc(book, listOf(chapter))
                    val store = AppBookSourceChangeStore(context, db, search)
                    val result = store.prepare(row, allowWebFile = false)
                    assertEquals(GSON.toJson(book), result.bookJson)
                    assertEquals(GSON.toJson(source), result.sourceJson)
                    assertEquals(GSON.toJson(chapter), result.chapters.single().json)
                    assertEquals("Tag", result.chapters.single().tag)
                    // Normal web files skip TOC while the automatic replacement path still uses it.
                    book.type = io.legado.app.constant.BookType.webFile
                    book.downloadUrls = listOf("https://download.invalid/file")
                    search.rememberToc(book, listOf(chapter))
                    assertTrue(store.prepare(row, allowWebFile = true).chapters.isEmpty())
                    assertEquals(
                        listOf(GSON.toJson(chapter)),
                        store.prepare(row, allowWebFile = false).chapters.map { it.json },
                    )
                    store.order(row, false)
                    store.disable(row)
                    val saved = db.bookSourceDao.getBookSource(source.bookSourceUrl)!!
                    assertEquals(9, saved.customOrder)
                    assertFalse(saved.enabled)
                    assertEquals("Comment", saved.bookSourceComment)
                    assertEquals("Group", saved.bookSourceGroup)
                }
            } finally {
                db.close()
            }
        }
}
