package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.utils.renameGroupExact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Groups are memberships on existing records, rather than independently stored entities. */
interface NamedGroupRepository {
    fun groups(): Flow<List<String>>
    suspend fun add(name: String)
    suspend fun rename(original: String, replacement: String?)
}

class ReplaceRuleGroupRepository(private val database: AppDatabase = appDb) : NamedGroupRepository {
    override fun groups() = database.replaceRuleDao.flowGroups()
    override suspend fun add(name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        database.withTransaction {
            val records = database.replaceRuleDao.noGroup.map { it.copy(group = name) }
            if (records.isNotEmpty()) database.replaceRuleDao.update(*records.toTypedArray())
        }
    }
    override suspend fun rename(original: String, replacement: String?) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val records = database.replaceRuleDao.getByGroup(original).mapNotNull { record ->
                record.group.renameGroupExact(original, replacement)?.let { record.copy(group = it) }
            }
            if (records.isNotEmpty()) database.replaceRuleDao.update(*records.toTypedArray())
        }
    }
}
