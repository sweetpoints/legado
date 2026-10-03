package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class ChapterSourceGroupRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun roomGroupObservationUpdatesEnabledGroupsWithoutMutatingEarlierProjection() = runBlocking {
        val first =
            BookSource(bookSourceUrl = "https://one", bookSourceName = "One", bookSourceGroup = "A")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(first) }
        val repository = RoomChapterSourceGroupRepository(database)
        val initial = withTimeout(5000) { repository.groups().first() }
        assertEquals(listOf("A"), initial)
        withContext(Dispatchers.IO) {
            database.bookSourceDao.insert(
                first.copy(bookSourceGroup = "B"),
                first.copy(
                    bookSourceUrl = "https://disabled",
                    bookSourceGroup = "Hidden",
                    enabled = false,
                ),
            )
        }
        val updated = withTimeout(5000) { repository.groups().first { "B" in it } }
        assertEquals(listOf("B"), updated)
        assertEquals(listOf("A"), initial)
    }
}
