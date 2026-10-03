package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AppBookSourceSearchRepositoryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun cleanup() {
        db.close()
    }

    @Test
    fun cachedRoomRowsKeepCompleteMetadataAndBookPolicyDoesNotPinCurrentOutsideFilter() =
        runBlocking {
            val currentSource =
                BookSource(
                    bookSourceUrl = "https://current",
                    bookSourceName = "Current",
                    bookSourceGroup = "A",
                    customOrder = 1,
                )
            val goodSource =
                currentSource.copy(
                    bookSourceUrl = "https://good",
                    bookSourceName = "Good",
                    customOrder = 2,
                )
            val current =
                SearchBook(
                    bookUrl = "https://current/book",
                    origin = currentSource.bookSourceUrl,
                    name = "Book",
                    author = "Author",
                    intro = "intro-current",
                    variable = "{\"field\":1}",
                    chapterWordCount = 50,
                    chapterWordCountText = "[1] First",
                    respondTime = 1,
                )
            val good =
                current.copy(
                    bookUrl = "https://good/book",
                    origin = goodSource.bookSourceUrl,
                    chapterWordCount = 150,
                    respondTime = 10,
                )
            withContext(Dispatchers.IO) {
                db.bookSourceDao.insert(currentSource, goodSource)
                db.searchBookDao.insert(current, good)
            }
            val store = AppBookSourceSearchStore(AppChapterSourceSearchStore(db), db)
            val repo = DefaultBookSourceSearchRepository(store)
            val result =
                repo.cached(
                    ChapterSourceSearchRequest(
                        "Book",
                        "Author",
                        group = "A",
                        currentBookUrl = current.bookUrl,
                        loadWordCount = true,
                        sortResponseTime = true,
                        filterMode = 1,
                        minimum = 100,
                        maximum = 200,
                    )
                )
            assertEquals(listOf(good.bookUrl), result.rows.map { it.id })
            assertEquals(2, result.allRows.size)
            val preserved =
                GSON.fromJson(
                    result.allRows.first { it.id == current.bookUrl }.json,
                    SearchBook::class.java,
                )
            assertEquals(current.intro, preserved.intro)
            assertEquals(current.variable, preserved.variable)
            assertTrue(
                withContext(Dispatchers.IO) { store.sourceExists(currentSource.bookSourceUrl) }
            )
            assertEquals(
                "Current",
                withContext(Dispatchers.IO) { store.sourceName(currentSource.bookSourceUrl) },
            )
        }
}
