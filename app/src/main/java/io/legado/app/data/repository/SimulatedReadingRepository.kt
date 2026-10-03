package io.legado.app.data.repository

import com.google.gson.JsonObject
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class SimulatedReadingSettings(val enabled: Boolean, val date: String, val start: String,
    val daily: String, val totalChapters: Int)
interface SimulatedReadingRepository {
    suspend fun load(bookUrl: String): SimulatedReadingSettings
    suspend fun save(bookUrl: String, value: SimulatedReadingSettings): Book
}
/** Patch only simulation fields on the latest row; preserve unknown read config and book metadata. */
class RoomSimulatedReadingRepository(private val database: AppDatabase = appDb,
    private val today: () -> LocalDate = LocalDate::now) : SimulatedReadingRepository {
    override suspend fun load(bookUrl: String) = withContext(Dispatchers.IO) {
        val book = requireNotNull(database.bookDao.getBook(bookUrl)) { "Book no longer exists" }
        SimulatedReadingSettings(book.getReadSimulating(), book.getStartDate()?.toString().orEmpty(),
            book.getStartChapter().toString(), book.getDailyChapters().toString(), book.totalChapterNum)
    }
    override suspend fun save(bookUrl: String, value: SimulatedReadingSettings): Book = withContext(Dispatchers.IO + NonCancellable) {
        database.runInTransaction<Book> {
            val book = requireNotNull(database.bookDao.getBook(bookUrl)) { "Book no longer exists" }
            val date = runCatching { LocalDate.parse(value.date) }.getOrElse { today() }
            val start = value.start.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val daily = value.daily.toIntOrNull()?.coerceAtLeast(1) ?: book.totalChapterNum.coerceAtLeast(1)
            val original = database.bookDao.getReadConfigJson(bookUrl)
            val json = GSON.fromJsonObject<JsonObject>(original).getOrNull() ?: JsonObject()
            // Obtain the same date encoding as Room's Book.ReadConfig converter.
            val patch = GSON.toJsonTree(Book.ReadConfig(readSimulating=value.enabled, startDate=date,
                startChapter=start, dailyChapters=daily)).asJsonObject
            listOf("readSimulating","startDate","startChapter","dailyChapters").forEach { json.add(it, patch.get(it)) }
            database.bookDao.updateReadConfigJson(bookUrl, GSON.toJson(json))
            requireNotNull(database.bookDao.getBook(bookUrl))
        }
    }
}
