package io.legado.app.data.repository

import android.os.Looper
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExploreResultsRepositoryTest {
    private lateinit var source: BookSource
    private lateinit var bookUrl: String

    @Before
    fun createFixture() {
        val id = UUID.randomUUID().toString()
        bookUrl = "https://explore-fixture/$id/book"
        source =
            BookSource(
                bookSourceUrl = "https://explore-fixture/$id/source",
                bookSourceName = "Explore repository fixture",
                exploreUrl = "One::https://one&&ERROR:excluded::https://excluded&&Two::https://two",
            )
        appDb.bookSourceDao.insert(source)
    }

    @After
    fun cleanFixture() {
        appDb.bookDao.getBook(bookUrl)?.let { appDb.bookDao.deleteRows(it) }
        appDb.bookSourceDao.delete(source.bookSourceUrl)
    }

    @Test
    fun sourceCategoriesPageAndCacheRetainRealDaoMetadataAndRunOffMain() = runBlocking {
        val repository =
            AppExploreResultsRepository(
                fetchPage = { snapshot, url, page ->
                    assertTrue(Looper.myLooper() != Looper.getMainLooper())
                    assertEquals(source.bookSourceUrl, snapshot.bookSourceUrl)
                    assertEquals("@js:exact full URL", url)
                    assertEquals(7, page)
                    listOf(
                        SearchBook(
                            bookUrl = bookUrl,
                            origin = snapshot.bookSourceUrl,
                            name = "Fixture name",
                            author = "Fixture author",
                            variable = "{\"token\":\"exact\"}",
                            tocUrl = "https://fixture/toc",
                        )
                    )
                }
            )
        val snapshot = repository.source(source.bookSourceUrl)
        assertEquals(listOf("One", "Two"), repository.categories(snapshot).map { it.title })
        val rows = repository.page(snapshot, "@js:exact full URL", 7)
        repository.cache(rows)
        val cached = appDb.searchBookDao.getSearchBook(bookUrl)!!
        assertEquals("{\"token\":\"exact\"}", cached.variable)
        assertEquals("https://fixture/toc", cached.tocUrl)
        assertEquals("Fixture name", rows.single().name)
    }

    @Test
    fun addingLoadedRowsUsesOriginalRoomDeduplicationAndMembershipContract() = runBlocking {
        val repository = AppExploreResultsRepository()
        val row =
            exploreResultsRow(
                SearchBook(
                    bookUrl = bookUrl,
                    origin = source.bookSourceUrl,
                    name = "Unique ${UUID.randomUUID()}",
                    author = "Fixture author",
                )
            )
        val first = repository.addToShelf(listOf(row))
        assertEquals(ExploreResultsAddResult(1, 0), first)
        assertNotNull(appDb.bookDao.getBook(bookUrl))
        val second = repository.addToShelf(listOf(row))
        assertEquals(ExploreResultsAddResult(0, 1), second)
        val membership = repository.membership().first { bookUrl in it }
        assertTrue(exploreResultsInShelf(row, membership))
        assertTrue("${row.name}-${row.author}" in membership)
    }
}
