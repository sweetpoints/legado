package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AppBookSourceWebFilePreparationTest {
    @Test
    fun actualWebBookInfoRulesFillMissingDownloadUrlsBeforeDeliveringNormalWebFileWithoutDirectory() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                withContext(Dispatchers.IO) {
                    val token = UUID.randomUUID().toString()
                    val origin = "https://$token.invalid"
                    val source =
                        BookSource(
                            bookSourceUrl = origin,
                            bookSourceName = "Files",
                            bookSourceType = BookSourceType.file,
                            ruleBookInfo = BookInfoRule(downloadUrls = "a@href"),
                        )
                    db.bookSourceDao.insert(source)
                    val raw =
                        SearchBook(
                            bookUrl = "$origin/book",
                            origin = origin,
                            name = "Book",
                            author = "Author",
                            type = BookType.text or BookType.webFile,
                        )
                    val row =
                        ChapterSourceSearchRow(
                            raw.bookUrl,
                            origin,
                            "Files",
                            raw.name,
                            raw.author,
                            "Latest",
                            null,
                            -1,
                            -1,
                            0,
                            0,
                            raw.type,
                            GSON.toJson(raw),
                        )
                    val search = AppChapterSourceSearchStore(db)
                    val book =
                        raw.toBook().apply {
                            infoHtml = "<html><a href='/download/book.txt'>download</a></html>"
                        }
                    assertNull(book.downloadUrls)
                    search.rememberToc(book, emptyList())
                    val result =
                        AppBookSourceChangeStore(context, db, search)
                            .prepare(row, allowWebFile = true)
                    assertEquals(
                        listOf("$origin/download/book.txt"),
                        GSON.fromJson(result.bookJson, Book::class.java).downloadUrls,
                    )
                    assertTrue(result.chapters.isEmpty())
                    assertEquals(GSON.toJson(source), result.sourceJson)
                }
            } finally {
                db.close()
            }
        }
}
