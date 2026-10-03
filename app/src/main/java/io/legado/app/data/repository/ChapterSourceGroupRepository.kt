package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal interface ChapterSourceGroupRepository { fun groups(): Flow<List<String>> }
internal class RoomChapterSourceGroupRepository(private val database: AppDatabase = appDb) : ChapterSourceGroupRepository {
    override fun groups(): Flow<List<String>> = database.bookSourceDao.flowEnabledGroups().map { it.toList() }.flowOn(Dispatchers.IO)
}
