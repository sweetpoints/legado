package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface BookGroupManagementRepository {
    fun observe(): Flow<List<BookGroupEditorSnapshot>>

    suspend fun setShown(id: Long, shown: Boolean)

    suspend fun reorder(ids: List<Long>)
}

class RoomBookGroupManagementRepository(private val database: AppDatabase = appDb) :
    BookGroupManagementRepository {
    override fun observe() =
        database.bookGroupDao
            .flowAll()
            .map { rows -> rows.map(BookGroupEditorSnapshot::from) }
            .flowOn(Dispatchers.IO)

    override suspend fun setShown(id: Long, shown: Boolean) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.bookGroupDao.getByID(id) ?: error("分组不存在")
                database.bookGroupDao.update(current.copy(show = shown))
            }
        }

    override suspend fun reorder(ids: List<Long>) =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = database.bookGroupDao.all
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
