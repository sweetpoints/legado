package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.utils.renameGroupExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookSourceGroupRepository(private val database: AppDatabase = appDb) : NamedGroupRepository {
    override fun groups() = database.bookSourceDao.flowGroups()

    override suspend fun add(name: String) =
        withContext(Dispatchers.IO) {
            if (name.isBlank()) return@withContext
            database.withTransaction {
                val records = database.bookSourceDao.noGroup.map { it.copy(bookSourceGroup = name) }
                if (records.isNotEmpty()) database.bookSourceDao.update(*records.toTypedArray())
            }
        }

    override suspend fun rename(original: String, replacement: String?) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val records =
                    database.bookSourceDao.getByGroup(original).mapNotNull { record ->
                        record.bookSourceGroup.renameGroupExact(original, replacement)?.let {
                            record.copy(bookSourceGroup = it)
                        }
                    }
                if (records.isNotEmpty()) database.bookSourceDao.update(*records.toTypedArray())
            }
        }
}
