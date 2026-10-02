package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssReadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class RssReadRecordItem(val key: String, val title: String, val record: String, val origin: String)
interface RssReadRecordRepository {
    suspend fun load(origin: String?): List<RssReadRecordItem>
    suspend fun count(origin: String?): Int
    suspend fun clear(origin: String?)
    suspend fun resolve(key: String, origin: String?): RssReadRecord?
}
internal fun rssReadRecordKey(record: String): String = MessageDigest.getInstance("SHA-256")
    .digest(record.toByteArray()).joinToString("") { "%02x".format(it) }

class RoomRssReadRecordRepository(private val database: AppDatabase = appDb) : RssReadRecordRepository {
    private fun records(origin: String?) = if (origin == null) database.rssReadRecordDao.getRecords()
        else database.rssReadRecordDao.getRecordsByOrigin(origin)
    override suspend fun load(origin: String?): List<RssReadRecordItem> = withContext(Dispatchers.IO) {
        records(origin).map { RssReadRecordItem(rssReadRecordKey(it.record), it.title.orEmpty(), it.record, it.origin) }
    }
    override suspend fun count(origin: String?): Int = withContext(Dispatchers.IO) {
        if (origin == null) database.rssReadRecordDao.countRecords else database.rssReadRecordDao.countRecordsByOrigin(origin)
    }
    override suspend fun clear(origin: String?) = withContext(Dispatchers.IO) {
        if (origin == null) database.rssReadRecordDao.deleteAllRecord() else database.rssReadRecordDao.deleteRecordsByOrigin(origin)
    }
    /** Resolve a small saved key in this filter, then re-read the row for current progress/type metadata. */
    override suspend fun resolve(key: String, origin: String?): RssReadRecord? = withContext(Dispatchers.IO) {
        val candidate = records(origin).firstOrNull { rssReadRecordKey(it.record) == key } ?: return@withContext null
        database.rssReadRecordDao.getRecord(candidate.record, candidate.origin)?.copy()
    }
}
