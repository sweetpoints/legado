package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TxtTocRuleSnapshot(
    val id: Long,
    val name: String = "",
    val rule: String = "",
    val replacement: String = "",
    val example: String? = null,
    val serialNumber: Int = -1,
    val enable: Boolean = true,
) {
    fun entity() = TxtTocRule(id, name, rule, replacement, example, serialNumber, enable)

    companion object {
        fun from(rule: TxtTocRule) =
            TxtTocRuleSnapshot(
                rule.id,
                rule.name,
                rule.rule,
                rule.replacement,
                rule.example,
                rule.serialNumber,
                rule.enable,
            )
    }
}

interface TxtTocRuleEditorRepository {
    suspend fun load(id: Long): TxtTocRuleSnapshot?

    suspend fun save(rule: TxtTocRuleSnapshot, requireExisting: Boolean = false): TxtTocRuleSnapshot
}

class RoomTxtTocRuleEditorRepository(private val database: AppDatabase = appDb) :
    TxtTocRuleEditorRepository {
    override suspend fun load(id: Long) =
        withContext(Dispatchers.IO) {
            database.txtTocRuleDao.get(id)?.let(TxtTocRuleSnapshot::from)
        }

    override suspend fun save(rule: TxtTocRuleSnapshot, requireExisting: Boolean) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.txtTocRuleDao.get(rule.id)
                check(!requireExisting || current != null) { "规则不存在" }
                val persisted =
                    if (current == null) rule
                    else rule.copy(serialNumber = current.serialNumber, enable = current.enable)
                database.txtTocRuleDao.insert(persisted.entity())
                persisted
            }
        }
}
