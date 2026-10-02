package io.legado.app.data.repository

import io.legado.app.constant.AppConst.DEFAULT_WEBDAV_ID
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class RemoteServerChoice(val id: Long, val name: String)
interface RemoteServerListRepository {
    val selected: Long
    val defaultId: Long get() = DEFAULT_WEBDAV_ID
    fun observe(): Flow<List<RemoteServerChoice>>
    fun select(id: Long)
    suspend fun delete(id: Long)
}
class RoomRemoteServerListRepository(private val database: AppDatabase = appDb) : RemoteServerListRepository {
    override val selected: Long get() = AppConfig.remoteServerId
    override fun observe() = database.serverDao.observeAll().map { rows -> rows.map { RemoteServerChoice(it.id, it.name) } }.flowOn(Dispatchers.IO)
    override fun select(id: Long) { AppConfig.remoteServerId = id }
    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) { database.serverDao.delete(id) }
}
