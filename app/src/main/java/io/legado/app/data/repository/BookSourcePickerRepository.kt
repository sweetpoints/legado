package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Full source payloads stay outside saved state and mutable Room entities stay outside UI state.
 */
data class BookSourcePickerItem(val url: String, val name: String, val group: String?) {
    val displayName: String
        get() = if (group.isNullOrBlank()) name else "$name ($group)"
}

interface BookSourcePickerRepository {
    fun observe(query: String): Flow<List<BookSourcePickerItem>>

    suspend fun source(url: String): String?

    suspend fun delay(): Int

    suspend fun saveDelay(value: Int)
}

class RoomBookSourcePickerRepository(
    private val database: AppDatabase = appDb,
    private val readDelay: () -> Int = { AppConfig.batchChangeSourceDelay },
    private val writeDelay: (Int) -> Unit = { AppConfig.batchChangeSourceDelay = it },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookSourcePickerRepository {
    override fun observe(query: String): Flow<List<BookSourcePickerItem>> =
        (if (query.isEmpty()) database.bookSourceDao.flowEnabled()
            else database.bookSourceDao.flowSearchEnabled(query))
            .map { sources ->
                sources.map {
                    BookSourcePickerItem(it.bookSourceUrl, it.bookSourceName, it.bookSourceGroup)
                }
            }
            .flowOn(dispatcher)

    override suspend fun source(url: String): String? =
        withContext(dispatcher) {
            database.bookSourceDao.getBookSource(url)?.let { GSON.toJson(it) }
        }

    override suspend fun delay(): Int = withContext(dispatcher) { readDelay().coerceIn(0, 9999) }

    override suspend fun saveDelay(value: Int) =
        withContext(dispatcher) {
            require(value in 0..9999)
            writeDelay(value)
        }
}
