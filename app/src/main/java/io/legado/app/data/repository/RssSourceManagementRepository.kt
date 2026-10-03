package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.help.DefaultData
import io.legado.app.help.source.SourceHelp
import io.legado.app.utils.*
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

sealed interface RssSourceManagementFilter {
    data object All : RssSourceManagementFilter

    data object Enabled : RssSourceManagementFilter

    data object Disabled : RssSourceManagementFilter

    data object Login : RssSourceManagementFilter

    data object NoGroup : RssSourceManagementFilter

    data class Group(val name: String) : RssSourceManagementFilter

    data class Search(val query: String) : RssSourceManagementFilter
}

data class RssSourceManagementRow(
    val id: String,
    val name: String,
    val displayName: String,
    val group: String?,
    val enabled: Boolean,
    val order: Int,
)

data class RssSourceManagementExport(val path: String, val name: String)

interface RssSourceManagementRepository {
    fun rows(filter: RssSourceManagementFilter): Flow<List<RssSourceManagementRow>>

    fun groups(): Flow<List<String>>

    suspend fun source(id: String): RssSource?

    suspend fun enabled(ids: List<String>, enabled: Boolean)

    suspend fun group(ids: List<String>, value: String, add: Boolean)

    suspend fun edge(ids: List<String>, top: Boolean)

    suspend fun move(id: String, target: String, after: Boolean)

    suspend fun delete(ids: List<String>)

    suspend fun export(ids: List<String>): RssSourceManagementExport

    suspend fun releaseExport(path: String)

    suspend fun importDefault()

    suspend fun importHistory(): List<String>

    suspend fun rememberImport(value: String)

    suspend fun forgetImport(value: String)
}

internal fun rssSourceManagementId(url: String): String =
    MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") {
        "%02x".format(it)
    }

internal fun rssSourceManagementRow(source: RssSource) =
    RssSourceManagementRow(
        rssSourceManagementId(source.sourceUrl),
        source.sourceName,
        source.getDisplayNameGroup(),
        source.sourceGroup,
        source.enabled,
        source.customOrder,
    )

/** All mutations start from current entities. UI rows never hold mutable source/parser objects. */
class AppRssSourceManagementRepository(
    private val database: AppDatabase = appDb,
    private val exportDirectory: File = File(appCtx.filesDir, "rss-source-management-exports"),
    private val deleteSources: (List<RssSource>) -> Unit = SourceHelp::deleteRssSources,
    private val defaults: () -> Unit = { DefaultData.importDefaultRssSources() },
    private val afterExport: (File) -> Unit = {},
) : RssSourceManagementRepository {
    private val dao
        get() = database.rssSourceDao

    override fun rows(filter: RssSourceManagementFilter): Flow<List<RssSourceManagementRow>> =
        when (filter) {
                RssSourceManagementFilter.All -> dao.flowAll()
                RssSourceManagementFilter.Enabled -> dao.flowEnabled()
                RssSourceManagementFilter.Disabled -> dao.flowDisabled()
                RssSourceManagementFilter.Login -> dao.flowLogin()
                RssSourceManagementFilter.NoGroup -> dao.flowNoGroup()
                is RssSourceManagementFilter.Group -> dao.flowGroupSearch(filter.name)
                is RssSourceManagementFilter.Search -> dao.flowSearch(filter.query)
            }
            .map { values -> values.map(::rssSourceManagementRow) }
            .flowOn(Dispatchers.IO)

    override fun groups() = dao.flowGroups()

    private fun current(ids: List<String>): List<RssSource> {
        val values = dao.all.associateBy { rssSourceManagementId(it.sourceUrl) }
        return ids.distinct().mapNotNull(values::get)
    }

    override suspend fun source(id: String): RssSource? =
        withContext(Dispatchers.IO) { current(listOf(id)).firstOrNull()?.copy() }

    override suspend fun enabled(ids: List<String>, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            database.withTransaction { current(ids).forEach { dao.enable(it.sourceUrl, enabled) } }
        }

    override suspend fun group(ids: List<String>, value: String, add: Boolean): Unit =
        withContext(Dispatchers.IO) {
            if (value.isEmpty()) return@withContext
            database.withTransaction {
                val values =
                    current(ids).map { source ->
                        source.copy().let { if (add) it.addGroup(value) else it.removeGroup(value) }
                    }
                if (values.isNotEmpty()) dao.update(*values.toTypedArray())
            }
        }

    override suspend fun edge(ids: List<String>, top: Boolean): Unit =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val values = current(ids).sortedBy { it.customOrder }
                if (values.isEmpty()) return@withTransaction
                val boundary = if (top) dao.minOrder - 1 else dao.maxOrder + 1
                dao.update(
                    *values
                        .mapIndexed { index, source ->
                            source.copy(
                                customOrder = if (top) boundary - index else boundary + index
                            )
                        }
                        .toTypedArray()
                )
            }
        }

    override suspend fun move(id: String, target: String, after: Boolean): Unit =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val values = dao.all
                val reordered =
                    moveRelativeTo(values, id, target, after) {
                        rssSourceManagementId(it.sourceUrl)
                    }
                if (reordered != values)
                    dao.update(
                        *reordered
                            .mapIndexed { index, source -> source.copy(customOrder = index) }
                            .toTypedArray()
                    )
            }
        }

    override suspend fun delete(ids: List<String>): Unit =
        withContext(Dispatchers.IO) {
            // SourceHelp also clears parsed articles, shared source state and source variables.
            database.withTransaction { current(ids).takeIf { it.isNotEmpty() }?.let(deleteSources) }
        }

    override suspend fun export(ids: List<String>): RssSourceManagementExport {
        var created: File? = null
        try {
            val result =
                withContext(Dispatchers.IO) {
                    val sources = database.withTransaction { current(ids).map { it.copy() } }
                    check(sources.isNotEmpty())
                    val name =
                        if (sources.size == 1)
                            "rssSource_${sources.first().sourceName.normalizeFileName()}.json"
                        else
                            "rssSource_${SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())}.json"
                    check(exportDirectory.isDirectory || exportDirectory.mkdirs())
                    val file = File(exportDirectory, "${UUID.randomUUID()}.json")
                    created = file
                    file.writeText(GSON.toJson(sources))
                    currentCoroutineContext().ensureActive()
                    afterExport(file)
                    RssSourceManagementExport(file.canonicalPath, name)
                }
            created = null
            return result
        } catch (error: Throwable) {
            withContext(Dispatchers.IO + NonCancellable) {
                created?.let { check(!it.exists() || it.delete()) }
            }
            throw error
        }
    }

    override suspend fun releaseExport(path: String): Unit =
        withContext(Dispatchers.IO + NonCancellable) {
            val file = File(path)
            val owned = exportDirectory.canonicalFile
            require(file.name.endsWith(".json"))
            require(
                UUID.fromString(file.name.removeSuffix(".json")).toString() ==
                    file.name.removeSuffix(".json")
            )
            require(
                file.canonicalFile.parentFile == owned &&
                    file.canonicalPath == File(owned, file.name).absolutePath
            )
            check(!file.exists() || file.delete())
        }

    override suspend fun importDefault(): Unit = withContext(Dispatchers.IO) { defaults() }

    private fun history(): List<String> =
        ACache.get(cacheDir = false).getAsString(IMPORT_HISTORY)?.splitNotBlank(",")?.toList()
            ?: emptyList()

    override suspend fun importHistory(): List<String> =
        withContext(Dispatchers.IO) { historyGate.withLock { history() } }

    override suspend fun rememberImport(value: String): Unit =
        withContext(Dispatchers.IO) {
            if (!value.isAbsUrl()) return@withContext
            historyGate.withLock {
                val values = history().toMutableList()
                if (value !in values) {
                    values.add(0, value)
                    ACache.get(cacheDir = false).put(IMPORT_HISTORY, values.joinToString(","))
                }
            }
        }

    override suspend fun forgetImport(value: String): Unit =
        withContext(Dispatchers.IO) {
            historyGate.withLock {
                val values = history().toMutableList()
                values.remove(value)
                ACache.get(cacheDir = false).put(IMPORT_HISTORY, values.joinToString(","))
            }
        }

    private companion object {
        const val IMPORT_HISTORY = "rssSourceRecordKey"
        val historyGate = Mutex()
    }
}
