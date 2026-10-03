package io.legado.app.data.repository

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ThemeListValue(val name: String, val json: String)

internal data class ThemeListItem(
    val key: String,
    val name: String,
    val json: String,
    val occurrence: Int,
)

internal interface ThemeListStore {
    suspend fun list(): List<ThemeListValue>

    suspend fun delete(json: String, occurrence: Int): Boolean

    suspend fun add(json: String): Boolean

    suspend fun apply(json: String)

    suspend fun writeShare(session: String, receipt: String, json: String)

    suspend fun readShare(session: String, receipt: String): String
}

internal interface ThemeListRepository {
    suspend fun list(): List<ThemeListItem>

    suspend fun delete(item: ThemeListItem): Boolean

    suspend fun add(json: String): Boolean

    suspend fun apply(item: ThemeListItem)

    suspend fun stageShare(session: String, receipt: String, item: ThemeListItem)

    suspend fun share(session: String, receipt: String): String
}

internal class DefaultThemeListRepository(
    private val store: ThemeListStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ThemeListRepository {
    override suspend fun list() =
        withContext(io) {
            val counts = mutableMapOf<String, Int>()
            store.list().map { value ->
                val digest =
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.json.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                val occurrence = counts[digest] ?: 0
                counts[digest] = occurrence + 1
                ThemeListItem("$digest:$occurrence", value.name, value.json, occurrence)
            }
        }

    override suspend fun delete(item: ThemeListItem) =
        withContext(io) { store.delete(item.json, item.occurrence) }

    override suspend fun add(json: String) = withContext(io) { store.add(json) }

    override suspend fun apply(item: ThemeListItem) = withContext(io) { store.apply(item.json) }

    override suspend fun stageShare(session: String, receipt: String, item: ThemeListItem) =
        withContext(io) { store.writeShare(session, receipt, item.json) }

    override suspend fun share(session: String, receipt: String) =
        withContext(io) { store.readShare(session, receipt) }
}
