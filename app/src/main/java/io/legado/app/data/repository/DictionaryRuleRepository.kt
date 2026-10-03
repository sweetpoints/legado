package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.DictRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DictionaryRuleSnapshot(
    val name: String = "",
    val urlRule: String = "",
    val showRule: String = "",
    val enabled: Boolean = true,
    val sortNumber: Int = 0,
) {
    fun entity() = DictRule(name, urlRule, showRule, enabled, sortNumber)

    companion object {
        fun from(rule: DictRule) =
            DictionaryRuleSnapshot(
                rule.name,
                rule.urlRule,
                rule.showRule,
                rule.enabled,
                rule.sortNumber,
            )
    }
}

interface DictionaryRuleRepository {
    suspend fun load(name: String): DictionaryRuleSnapshot?

    suspend fun save(previousName: String?, rule: DictionaryRuleSnapshot)
}

class RoomDictionaryRuleRepository(private val database: AppDatabase = appDb) :
    DictionaryRuleRepository {
    override suspend fun load(name: String) =
        withContext(Dispatchers.IO) {
            database.dictRuleDao.getByName(name)?.let(DictionaryRuleSnapshot::from)
        }

    override suspend fun save(previousName: String?, rule: DictionaryRuleSnapshot) =
        withContext(Dispatchers.IO) {
            // Rename and insert are one transaction: an insert failure cannot remove the old rule.
            database.withTransaction {
                previousName
                    ?.let { database.dictRuleDao.getByName(it) }
                    ?.let { database.dictRuleDao.delete(it) }
                database.dictRuleDao.insert(rule.entity())
            }
        }
}
