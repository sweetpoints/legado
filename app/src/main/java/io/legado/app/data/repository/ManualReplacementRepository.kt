package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ManualReplacementRow(val id: Long, val name: String)
interface ManualReplacementRepository {
    suspend fun candidates(source: Boolean): List<ManualReplacementRow>
}
class AppManualReplacementRepository(private val database: AppDatabase = appDb) : ManualReplacementRepository {
    override suspend fun candidates(source: Boolean) = withContext(Dispatchers.IO) {
        (if (source) database.replaceRuleDao.findEnabledBySourceScope() else database.replaceRuleDao.findManualCandidates())
            .map { ManualReplacementRow(it.id, it.getDisplayNameGroup()) }.distinctBy { it.id }
    }
}
