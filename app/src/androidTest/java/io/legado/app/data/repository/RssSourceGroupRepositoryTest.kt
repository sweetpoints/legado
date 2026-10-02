package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class RssSourceGroupRepositoryTest {
    private lateinit var database: AppDatabase
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build() }
    @After fun cleanup() { database.close() }
    @Test fun renameAndDeletePreserveFullSourceAndOnlyExactMemberships() = runBlocking {
        val exact = RssSource(sourceUrl = "https://exact.invalid", sourceName = "Source", sourceGroup = "One,Other", enabled = false, header = "header", loginUrl = "login", articleStyle = 2, ruleContent = "content-rule", sortUrl = "sort")
        val similar = exact.copy(sourceUrl = "https://similar.invalid", sourceGroup = "OneMore")
        withContext(Dispatchers.IO) { database.rssSourceDao.insert(exact, similar) }
        val repository = RssSourceGroupRepository(database)
        repository.rename("One", "Renamed")
        withContext(Dispatchers.IO) {
            assertEquals(GSON.toJson(exact.copy(sourceGroup = "Other,Renamed")), GSON.toJson(database.rssSourceDao.getByKey(exact.sourceUrl)))
            assertEquals(GSON.toJson(similar), GSON.toJson(database.rssSourceDao.getByKey(similar.sourceUrl)))
        }
        repository.rename("Renamed", null)
        withContext(Dispatchers.IO) { assertEquals("Other", database.rssSourceDao.getByKey(exact.sourceUrl)!!.sourceGroup) }
    }
    @Test fun addAssignsOnlyNullAndEmptyGroupsAndBlankAddIsNoOp() = runBlocking {
        val first = RssSource(sourceUrl = "https://null.invalid", sourceGroup = null)
        val second = first.copy(sourceUrl = "https://empty.invalid", sourceGroup = "")
        val third = first.copy(sourceUrl = "https://group.invalid", sourceGroup = "Existing")
        withContext(Dispatchers.IO) { database.rssSourceDao.insert(first, second, third) }
        val repository = RssSourceGroupRepository(database)
        repository.add(" "); repository.add(" Exact ")
        withContext(Dispatchers.IO) {
            assertEquals(" Exact ", database.rssSourceDao.getByKey(first.sourceUrl)!!.sourceGroup)
            assertEquals(" Exact ", database.rssSourceDao.getByKey(second.sourceUrl)!!.sourceGroup)
            assertEquals(GSON.toJson(third), GSON.toJson(database.rssSourceDao.getByKey(third.sourceUrl)))
        }
    }
}
