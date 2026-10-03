package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Each highlight rule has one complete group label; commas are ordinary label characters. */
internal interface HighlightGroupRepository {
    fun groups(): Flow<List<String>>

    suspend fun rename(source: String, replacement: String)

    suspend fun delete(source: String)

    suspend fun move(source: String, target: String?)
}

internal class RoomHighlightGroupRepository(
    private val database: AppDatabase = appDb,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : HighlightGroupRepository {
    override fun groups() = database.highlightRuleDao.flowGroups().flowOn(io)

    override suspend fun rename(source: String, replacement: String) =
        withContext(io) {
            replacement
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let { database.highlightRuleDao.moveGroup(source, it) }
            Unit
        }

    override suspend fun delete(source: String) =
        withContext(io) { database.highlightRuleDao.deleteGroup(source) }

    override suspend fun move(source: String, target: String?) =
        withContext(io) {
            database.highlightRuleDao.moveGroup(source, target?.trim()?.takeIf { it.isNotEmpty() })
        }
}
