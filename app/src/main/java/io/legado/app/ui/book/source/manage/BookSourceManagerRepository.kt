package io.legado.app.ui.book.source.manage

import android.content.Context
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.toBookSource
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.moveRelativeTo
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.writeToOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Database entities never cross the UI boundary. Every mutation resolves current records on IO. */
internal interface BookSourceManagerRepository {
    fun sources(query: String): Flow<List<SourceManagerRow>>

    fun allSources(): Flow<List<SourceManagerRow>>

    fun groups(): Flow<List<String>>

    fun counts(): Flow<Map<String, Int>>

    suspend fun mutate(keys: List<String>, action: SourceMutation, value: String = "")

    suspend fun move(key: String, target: String, after: Boolean)

    suspend fun export(keys: List<String>): SourceExport

    suspend fun resolve(keys: List<String>): List<BookSourcePart>

    suspend fun importHistory(): List<String>

    suspend fun rememberImport(value: String)

    suspend fun forgetImport(value: String)
}

internal enum class SourceMutation {
    DELETE,
    ENABLE,
    DISABLE,
    ENABLE_EXPLORE,
    DISABLE_EXPLORE,
    TOP,
    BOTTOM,
    ADD_GROUP,
    REMOVE_GROUP,
}

internal data class SourceExport(val file: File, val name: String, val mime: String)

internal class AppBookSourceManagerRepository(private val context: Context) :
    BookSourceManagerRepository {
    private val dao
        get() = appDb.bookSourceDao

    override suspend fun importHistory(): List<String> =
        withContext(Dispatchers.IO) {
            ACache.get(cacheDir = false)
                .getAsString("bookSourceRecordKey")
                ?.splitNotBlank(",")
                ?.toList()
                .orEmpty()
        }

    override suspend fun rememberImport(value: String) =
        withContext(Dispatchers.IO) {
            if (value.isAbsUrl()) {
                val history = importHistory()
                if (value !in history)
                    ACache.get(cacheDir = false)
                        .put("bookSourceRecordKey", (listOf(value) + history).joinToString(","))
            }
        }

    override suspend fun forgetImport(value: String) =
        withContext(Dispatchers.IO) {
            ACache.get(cacheDir = false)
                .put(
                    "bookSourceRecordKey",
                    importHistory().filter { it != value }.joinToString(","),
                )
        }

    override fun sources(query: String): Flow<List<SourceManagerRow>> {
        val flow =
            when (query) {
                "" -> dao.flowAll()
                context.getString(io.legado.app.R.string.enabled) -> dao.flowEnabled()
                context.getString(io.legado.app.R.string.disabled) -> dao.flowDisabled()
                context.getString(io.legado.app.R.string.need_login) -> dao.flowLogin()
                context.getString(io.legado.app.R.string.no_group) -> dao.flowNoGroup()
                context.getString(io.legado.app.R.string.enabled_explore) ->
                    dao.flowEnabledExplore()
                context.getString(io.legado.app.R.string.disabled_explore) ->
                    dao.flowDisabledExplore()
                else ->
                    if (query.startsWith("group:"))
                        dao.flowGroupSearch(query.removePrefix("group:"))
                    else dao.flowSearch(query)
            }
        return flow.map { it.map(SourceManagerRow::from) }.flowOn(Dispatchers.IO)
    }

    override fun allSources() =
        dao.flowAll().map { it.map(SourceManagerRow::from) }.flowOn(Dispatchers.IO)

    override fun groups() = dao.flowGroups().flowOn(Dispatchers.IO)

    override fun counts() =
        appDb.bookDao
            .flowBookshelfSourceOrigins()
            .map { it.groupingBy { key -> key }.eachCount() }
            .flowOn(Dispatchers.IO)

    override suspend fun resolve(keys: List<String>) =
        withContext(Dispatchers.IO) {
            val selected = keys.toSet()
            dao.allPart
                .filter { it.bookSourceUrl in selected }
                .sortedBy { keys.indexOf(it.bookSourceUrl) }
        }

    override suspend fun mutate(keys: List<String>, action: SourceMutation, value: String) =
        withContext(Dispatchers.IO) {
            appDb.runInTransaction {
                val selected = keys.toSet()
                val rows = dao.allPart.filter { it.bookSourceUrl in selected }
                when (action) {
                    SourceMutation.DELETE -> SourceHelp.deleteBookSourceParts(rows)
                    SourceMutation.ENABLE,
                    SourceMutation.DISABLE -> dao.enable(action == SourceMutation.ENABLE, rows)
                    SourceMutation.ENABLE_EXPLORE,
                    SourceMutation.DISABLE_EXPLORE ->
                        dao.enableExplore(action == SourceMutation.ENABLE_EXPLORE, rows)
                    SourceMutation.TOP -> {
                        val base = dao.minOrder - 1
                        dao.upOrder(
                            rows
                                .sortedBy { it.customOrder }
                                .mapIndexed { i, it -> it.copy(customOrder = base - i) }
                        )
                    }
                    SourceMutation.BOTTOM -> {
                        val base = dao.maxOrder + 1
                        dao.upOrder(
                            rows
                                .sortedBy { it.customOrder }
                                .mapIndexed { i, it -> it.copy(customOrder = base + i) }
                        )
                    }
                    SourceMutation.ADD_GROUP,
                    SourceMutation.REMOVE_GROUP ->
                        dao.upGroup(
                            rows.map {
                                it.copy().apply {
                                    if (action == SourceMutation.ADD_GROUP) addGroup(value)
                                    else removeGroup(value)
                                }
                            }
                        )
                }
            }
        }

    override suspend fun move(key: String, target: String, after: Boolean) =
        withContext(Dispatchers.IO) {
            appDb.runInTransaction {
                val current = dao.allPart
                val reordered = moveRelativeTo(current, key, target, after) { it.bookSourceUrl }
                if (reordered != current)
                    dao.upOrder(reordered.mapIndexed { i, it -> it.copy(customOrder = i) })
            }
        }

    override suspend fun export(keys: List<String>): SourceExport =
        withContext(Dispatchers.IO) {
            val rows = resolve(keys)
            val sources = rows.toBookSource()
            val single = sources.singleOrNull()
            val dir =
                File(context.cacheDir, "source-manager-exports/${UUID.randomUUID()}").apply {
                    mkdirs()
                }
            if (single != null && single.isJsSource()) {
                val name = "${single.bookSourceName.normalizeFileName()}.js"
                SourceExport(
                    File(dir, name).apply { writeText(single.mainJs.orEmpty()) },
                    name,
                    "text/javascript",
                )
            } else {
                val name =
                    if (single != null)
                        "bookSource_${single.bookSourceName.normalizeFileName()}.json"
                    else
                        "bookSource_${SimpleDateFormat("yyyyMMddHHmm",Locale.getDefault()).format(Date())}.json"
                SourceExport(
                    File(dir, name).apply {
                        outputStream().buffered().use { GSON.writeToOutputStream(it, sources) }
                    },
                    name,
                    "application/json",
                )
            }
        }
}
