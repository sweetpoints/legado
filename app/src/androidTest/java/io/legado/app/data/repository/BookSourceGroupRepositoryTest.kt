package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class BookSourceGroupRepositoryTest {
    private lateinit var database: AppDatabase
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build() }
    @After fun cleanup() { database.close() }
    @Test fun renameAndDeletePreserveFullSourceAndOnlyExactMemberships() = runBlocking {
        val exact = BookSource(bookSourceUrl = "https://exact.invalid", bookSourceName = "Source", bookSourceGroup = "One,Other", enabled = false, header = "header", loginUrl = "login", customOrder = 42, lastUpdateTime = 987)
        val similar = exact.copy(bookSourceUrl = "https://similar.invalid", bookSourceGroup = "OneMore")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(exact, similar) }
        val repository = BookSourceGroupRepository(database)
        repository.rename("One", "Renamed")
        withContext(Dispatchers.IO) {
            assertEquals(GSON.toJson(exact.copy(bookSourceGroup = "Other,Renamed")), GSON.toJson(database.bookSourceDao.getBookSource(exact.bookSourceUrl)))
            assertEquals(GSON.toJson(similar), GSON.toJson(database.bookSourceDao.getBookSource(similar.bookSourceUrl)))
        }
        repository.rename("Renamed", null)
        withContext(Dispatchers.IO) { assertEquals("Other", database.bookSourceDao.getBookSource(exact.bookSourceUrl)!!.bookSourceGroup) }
    }
    @Test fun addAssignsOnlyNullAndEmptyGroupsAndBlankAddIsNoOp() = runBlocking {
        val first = BookSource(bookSourceUrl = "https://null.invalid", bookSourceGroup = null)
        val second = first.copy(bookSourceUrl = "https://empty.invalid", bookSourceGroup = "")
        val third = first.copy(bookSourceUrl = "https://group.invalid", bookSourceGroup = "Existing")
        withContext(Dispatchers.IO) { database.bookSourceDao.insert(first, second, third) }
        val repository = BookSourceGroupRepository(database)
        repository.add(" "); repository.add(" Exact ")
        withContext(Dispatchers.IO) {
            assertEquals(" Exact ", database.bookSourceDao.getBookSource(first.bookSourceUrl)!!.bookSourceGroup)
            assertEquals(" Exact ", database.bookSourceDao.getBookSource(second.bookSourceUrl)!!.bookSourceGroup)
            assertEquals(GSON.toJson(third), GSON.toJson(database.bookSourceDao.getBookSource(third.bookSourceUrl)))
        }
    }
}
