package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class AppChapterSourceSearchStoreTest {
    private lateinit var db: AppDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build() }
    @After fun cleanup() { db.close() }
    @Test fun actualCachedQueriesPreserveAllFieldsAndRespectAuthorEnabledExactGroupAndSearchText() = runBlocking {
        val token = UUID.randomUUID().toString()
        val selected = BookSource(bookSourceUrl = "https://$token/selected", bookSourceName = "Selected", bookSourceGroup = "A,B", customOrder = 7)
        val similar = selected.copy(bookSourceUrl = "https://$token/similar", bookSourceGroup = "AA", customOrder = 1)
        val disabled = selected.copy(bookSourceUrl = "https://$token/disabled", enabled = false)
        val exact = SearchBook(bookUrl = "https://$token/book", origin = selected.bookSourceUrl, originName = "Chosen", name = "Book", author = "Author", coverUrl = "cover", intro = "intro", tocUrl = "toc", variable = "{\"field\":1}", kind = "kind", latestChapterTitle = "Latest", chapterWordCount = 1500, respondTime = 40, chapterWordCountText = "[3] Latest", originOrder = 99)
        withContext(Dispatchers.IO) {
            db.bookSourceDao.insert(selected, similar, disabled)
            db.searchBookDao.insert(exact, exact.copy(bookUrl = "other", origin = similar.bookSourceUrl), exact.copy(bookUrl = "disabled", origin = disabled.bookSourceUrl), exact.copy(bookUrl = "wrong-author", author = "Different"))
        }
        val repo = DefaultChapterSourceSearchRepository(AppChapterSourceSearchStore(db))
        val request = ChapterSourceSearchRequest("Book", "Author", group = "A")
        val rows = repo.cached(request).rows
        assertEquals(listOf(exact.bookUrl), rows.map { it.id })
        assertEquals(GSON.toJson(exact.copy(originOrder = 7)), rows.single().json)
        assertEquals(listOf(exact.bookUrl), repo.cached(request.copy(query = "Latest")).rows.map { it.id })
        assertTrue(repo.cached(request.copy(query = "missing")).rows.isEmpty())
        assertEquals(2, repo.cached(request.copy(checkAuthor = false)).rows.size)
        assertEquals(setOf(exact.bookUrl, "other"), repo.cached(request.copy(group = "")).rows.map { it.id }.toSet())
    }
    @Test fun sourceSelectionFallsBackOnlyWhenConfiguredGroupHasNoEnabledSourceAndResetDeletesCapturedRowsOnly() = runBlocking {
        val token = UUID.randomUUID().toString(); val first = BookSource(bookSourceUrl = "https://$token/first", bookSourceName = "First", bookSourceGroup = "A", customOrder = 1)
        val second = first.copy(bookSourceUrl = "https://$token/second", bookSourceName = "Second", bookSourceGroup = "B", customOrder = 2)
        val target = SearchBook(bookUrl = "target-$token", origin = first.bookSourceUrl, originName = "First", name = "Book", author = "Author")
        val unrelated = target.copy(bookUrl = "other-$token", name = "Other")
        withContext(Dispatchers.IO) { db.bookSourceDao.insert(first, second); db.searchBookDao.insert(target, unrelated) }
        val store = AppChapterSourceSearchStore(db); val request = ChapterSourceSearchRequest("Book", "Author", group = "A")
        withContext(Dispatchers.IO) {
            assertEquals(ChapterSourceSearchSources(listOf(first.bookSourceUrl), "A"), store.sources(request))
            assertEquals(ChapterSourceSearchSources(listOf(first.bookSourceUrl, second.bookSourceUrl), ""), store.sources(request.copy(group = "missing")))
            val rows = store.cached(request); store.reset(rows)
            assertNull(db.searchBookDao.getSearchBook(target.bookUrl)); assertNotNull(db.searchBookDao.getSearchBook(unrelated.bookUrl))
            store.persist(rows.single()); assertEquals(GSON.toJson(target.copy(originOrder = 1)), GSON.toJson(db.searchBookDao.getSearchBook(target.bookUrl)))
        }
    }
}
