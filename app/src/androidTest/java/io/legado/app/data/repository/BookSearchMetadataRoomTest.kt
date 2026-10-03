package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchKeyword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchMetadataRoomTest {
    @Test
    fun actualHistoryTransactionPreservesConcurrentCountsQueryOrderingAndFreshSuggestions() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val repository =
                    DefaultBookSearchMetadataRepository(
                        AppBookSearchMetadataStore(database),
                        clock = { 20 },
                    )
                withContext(Dispatchers.IO) {
                    database.searchKeywordDao.insert(
                        SearchKeyword("first", usage = 5, lastUseTime = 1),
                        SearchKeyword("second", usage = 1, lastUseTime = 2),
                    )
                    database.bookDao.insert(
                        Book(bookUrl = "synthetic-search", name = "Title", author = "Author")
                    )
                }
                assertEquals(
                    listOf("second", "first"),
                    repository.history("").first().map { it.word },
                )
                assertEquals(
                    listOf("first", "second"),
                    repository.history("%").first().map { it.word },
                )
                coroutineScope {
                    repeat(10) { launch { repository.saveHistory("first") } }
                }
                assertEquals(15, repository.history("first").first().single().usage)
                val original = repository.suggestions("Title").first().single()
                withContext(Dispatchers.IO) {
                    database.bookDao.insert(
                        Book(bookUrl = "synthetic-search", name = "Updated", author = "Author")
                    )
                }
                assertEquals("Title", original.name)
                assertEquals("Updated", repository.suggestions("Updated").first().single().name)
                repository.deleteHistory("first")
                assertEquals(listOf("second"), repository.history("").first().map { it.word })
                repository.clearHistory()
                assertTrue(repository.history("").first().isEmpty())
            } finally {
                database.close()
            }
        }
}
