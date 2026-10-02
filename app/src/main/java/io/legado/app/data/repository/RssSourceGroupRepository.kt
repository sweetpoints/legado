package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.utils.renameGroupExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RssSourceGroupRepository(private val database: AppDatabase = appDb) : NamedGroupRepository {
    override fun groups() = database.rssSourceDao.flowGroups()
    override suspend fun add(name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        database.withTransaction {
            val records = database.rssSourceDao.noGroup.map { it.copy(sourceGroup = name) }
            if (records.isNotEmpty()) database.rssSourceDao.update(*records.toTypedArray())
        }
    }
    override suspend fun rename(original: String, replacement: String?) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val records = database.rssSourceDao.getByGroup(original).mapNotNull { record ->
                record.sourceGroup.renameGroupExact(original, replacement)?.let { record.copy(sourceGroup = it) }
            }
            if (records.isNotEmpty()) database.rssSourceDao.update(*records.toTypedArray())
        }
    }
}
