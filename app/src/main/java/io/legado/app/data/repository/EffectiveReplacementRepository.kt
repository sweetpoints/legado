package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EffectiveReplacementRow(val id: Long, val name: String, val conversion: Boolean = false) {
    val key: String get() = if (conversion) "conversion" else "rule:$id"
}
data class EffectiveReplacementSnapshot(val rows: List<EffectiveReplacementRow>, val conversion: Int)
interface EffectiveReplacementRepository {
    suspend fun load(sourceIds: List<Long>?, readerRows: List<EffectiveReplacementRow>): EffectiveReplacementSnapshot
    suspend fun disable(id: Long)
    suspend fun conversion(mode: Int)
}
class AppEffectiveReplacementRepository(private val database: AppDatabase = appDb) : EffectiveReplacementRepository {
    override suspend fun load(sourceIds: List<Long>?, readerRows: List<EffectiveReplacementRow>) = withContext(Dispatchers.IO) {
        val rows = sourceIds?.let { database.replaceRuleDao.findByIds(*it.toLongArray()).map { rule -> EffectiveReplacementRow(rule.id, rule.name) } }
            ?: readerRows.toList()
        val conversion = AppConfig.chineseConverterType
        EffectiveReplacementSnapshot(rows.distinctBy { it.key }, conversion)
    }
    override suspend fun disable(id: Long) = withContext(Dispatchers.IO) { database.replaceRuleDao.enable(id, false) }
    override suspend fun conversion(mode: Int) = withContext(Dispatchers.IO) {
        require(mode in 0..2)
        AppConfig.chineseConverterType = mode
    }
}
