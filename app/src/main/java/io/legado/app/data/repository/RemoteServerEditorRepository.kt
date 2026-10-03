package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RemoteServerEditorRepository {
    suspend fun load(id: Long?): Server

    suspend fun save(server: Server)
}

class RoomRemoteServerEditorRepository(private val database: AppDatabase = appDb) :
    RemoteServerEditorRepository {
    override suspend fun load(id: Long?): Server =
        withContext(Dispatchers.IO) {
            if (id == null) Server() else database.serverDao.get(id)?.copy() ?: error("服务器不存在")
        }

    // A single REPLACE statement is atomic and preserves identity and sort metadata.
    override suspend fun save(server: Server): Unit =
        withContext(Dispatchers.IO) { database.serverDao.insert(server) }
}
