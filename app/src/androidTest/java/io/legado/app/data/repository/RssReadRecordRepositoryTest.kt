package io.legado.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.RssReadRecord
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssReadRecordRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomRssReadRecordRepository
    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = RoomRssReadRecordRepository(database)
        withContext(Dispatchers.IO) { database.rssReadRecordDao.insertRecord(
            RssReadRecord("https://one", "Older", 10, origin = "one"),
            RssReadRecord("https://two", "Newest", 30, origin = "two"),
            RssReadRecord("https://blank", "Blank", 20, origin = "")) }
    }
    @After fun cleanup() { database.close() }
    @Test fun realDaoKeepsDescendingTimeAndNullVersusEmptyOriginSemantics() = runBlocking {
        assertEquals(listOf("Newest", "Blank", "Older"), repository.load(null).map { it.title })
        assertEquals(listOf("Blank"), repository.load("").map { it.title }); assertEquals(3, repository.count(null)); assertEquals(1, repository.count(""))
        assertEquals(listOf("Older"), repository.load("one").map { it.title }); assertTrue(repository.load("absent").isEmpty())
    }
    @Test fun confirmedFilteredDeleteKeepsOtherOriginsAndGlobalDeleteRemovesAll() = runBlocking {
        repository.clear(""); assertEquals(0, repository.count("")); assertEquals(2, repository.count(null))
        assertEquals(listOf("Older"), repository.load("one").map { it.title }); repository.clear(null); assertTrue(repository.load(null).isEmpty())
    }
    @Test fun smallKeysResolveLatestArticleMetadataWithoutCrossingOriginAndDisappearAfterDeletion() = runBlocking {
        val item = repository.load("one").single()
        withContext(Dispatchers.IO) {
            val current = database.rssReadRecordDao.getRecord(item.record, "one")!!
            database.rssReadRecordDao.update(current.copy(title = "Changed", durPos = 90, type = 2, image = "image", sort = "group", pubDate = "date"))
        }
        val current = repository.resolve(item.key, "one")!!
        assertEquals("Changed", current.title); assertEquals(90, current.durPos); assertEquals(2, current.type); assertEquals("group", current.sort); assertEquals("image", current.image); assertEquals("date", current.pubDate)
        assertNull(repository.resolve(item.key, "two")); repository.clear("one"); assertNull(repository.resolve(item.key, "one"))
        assertEquals(64, rssReadRecordKey("content:" + "x".repeat(100000)).length)
    }
}
