package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface BookGroupSelectionRepository {
    fun observe(): Flow<List<BookGroupEditorSnapshot>>

    suspend fun reorder(ids: List<Long>)
}

class RoomBookGroupSelectionRepository(private val database: AppDatabase = appDb) :
    BookGroupSelectionRepository {
    override fun observe() =
        database.bookGroupDao
            .flowSelect()
            .map { rows -> rows.map(BookGroupEditorSnapshot::from) }
            .flowOn(Dispatchers.IO)

    override suspend fun reorder(ids: List<Long>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.bookGroupDao.all.filter { it.groupId >= 0 }
                val byId = current.associateBy { it.groupId }
                val requested = ids.distinct().filter(byId::containsKey)
                val order =
                    requested + current.map { it.groupId }.filterNot(requested.toSet()::contains)
                database.bookGroupDao.update(
                    *order
                        .mapIndexed { index, id -> byId.getValue(id).copy(order = index + 1) }
                        .toTypedArray()
                )
            }
        }
}
