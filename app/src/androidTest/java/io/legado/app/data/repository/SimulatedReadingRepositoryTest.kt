package io.legado.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.time.LocalDate
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class SimulatedReadingRepositoryTest {
    private lateinit var database: AppDatabase
    private val book =
        Book(
            bookUrl = "fixture://simulation",
            totalChapterNum = 12,
            durChapterIndex = 5,
            customCoverUrl = "custom",
            persistedCoverUrl = "persisted",
            readConfig = Book.ReadConfig(pageAnim = 4),
        )
    private lateinit var repository: RoomSimulatedReadingRepository

    @Before
    fun before() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    AppDatabase::class.java,
                )
                .build()
        repository = RoomSimulatedReadingRepository(database) { LocalDate.of(2026, 10, 3) }
        runBlocking(Dispatchers.IO) { database.bookDao.insert(book) }
    }

    @After
    fun after() {
        database.close()
    }

    @Test
    fun disabledSimulationLoadsCurrentChapterAndOriginalDailyCount() = runBlocking {
        val value = repository.load(book.bookUrl)
        assertFalse(value.enabled)
        assertEquals("5", value.start)
        assertEquals("3", value.daily)
    }

    @Test
    fun savePatchesOnlySimulationFieldsOnLatestRowIncludingUnknownConfig() = runBlocking {
        withContext(Dispatchers.IO) {
            database.bookDao.updateReadConfigJson(
                book.bookUrl,
                "{\"future\":\"preserve\",\"pageAnim\":7,\"tocExpanded\":false}",
            )
            database.bookDao.insert(
                database.bookDao
                    .getBook(book.bookUrl)!!
                    .copy(customCoverUrl = "latest", durChapterPos = 99)
            )
            // Unknown future fields are not represented by Book.ReadConfig; add them after the row
            // update.
            database.bookDao.updateReadConfigJson(
                book.bookUrl,
                "{\"future\":\"preserve\",\"pageAnim\":7,\"tocExpanded\":false}",
            )
        }
        val saved =
            repository.save(
                book.bookUrl,
                SimulatedReadingSettings(true, "2026-09-30", "2", "4", 999),
            )
        assertEquals("latest", saved.customCoverUrl)
        assertEquals("persisted", saved.persistedCoverUrl)
        assertEquals(99, saved.durChapterPos)
        assertEquals(7, saved.readConfig!!.pageAnim)
        assertEquals(LocalDate.of(2026, 9, 30), saved.getStartDate())
        assertEquals(2, saved.getStartChapter())
        assertEquals(4, saved.getDailyChapters())
        withContext(Dispatchers.IO) {
            val json =
                GSON.fromJsonObject<com.google.gson.JsonObject>(
                        database.bookDao.getReadConfigJson(book.bookUrl)!!
                    )
                    .getOrThrow()
            assertEquals("preserve", json.get("future").asString)
            assertFalse(json.get("tocExpanded").asBoolean)
        }
    }

    @Test
    fun emptyOverflowAndInvalidDateKeepLegacyDefaultsAndUseLatestChapterCount() = runBlocking {
        val saved =
            repository.save(
                book.bookUrl,
                SimulatedReadingSettings(true, "invalid", "999999999999", "", 99),
            )
        assertEquals(0, saved.getStartChapter())
        assertEquals(12, saved.getDailyChapters())
        assertEquals(LocalDate.of(2026, 10, 3), saved.getStartDate())
        val clamped =
            repository.save(
                book.bookUrl,
                SimulatedReadingSettings(true, "2026-10-03", "-8", "0", 99),
            )
        assertEquals(0, clamped.getStartChapter())
        assertEquals(1, clamped.getDailyChapters())
    }

    @Test
    fun deletedOwnerIsNeverRecreatedByLateSave() = runBlocking {
        withContext(Dispatchers.IO) { database.bookDao.deleteRows(book) }
        assertTrue(
            runCatching {
                repository.save(book.bookUrl, SimulatedReadingSettings(true, "", "1", "3", 12))
            }
                .isFailure
        )
        withContext(Dispatchers.IO) { assertNull(database.bookDao.getBook(book.bookUrl)) }
    }
}
