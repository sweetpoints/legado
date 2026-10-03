package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReplacePreviewConfig
import io.legado.app.utils.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

sealed interface ReplaceManagementFilter {
    data object All : ReplaceManagementFilter

    data object Enabled : ReplaceManagementFilter

    data object Disabled : ReplaceManagementFilter

    data object NoGroup : ReplaceManagementFilter

    data class Group(val name: String) : ReplaceManagementFilter

    data class Search(val query: String) : ReplaceManagementFilter
}

internal fun replaceManagementFilter(
    query: String,
    enabled: String,
    disabled: String,
    noGroup: String,
): ReplaceManagementFilter =
    when {
        query.isEmpty() -> ReplaceManagementFilter.All
        query == enabled -> ReplaceManagementFilter.Enabled
        query == disabled -> ReplaceManagementFilter.Disabled
        query == noGroup -> ReplaceManagementFilter.NoGroup
        query.startsWith("group:") -> ReplaceManagementFilter.Group(query.substringAfter("group:"))
        else -> ReplaceManagementFilter.Search(query)
    }

data class ReplaceManagementRow(
    val id: Long,
    val name: String,
    val displayName: String,
    val group: String?,
    val enabled: Boolean,
    val order: Int,
)

data class ReplaceManagementExport(val path: String, val name: String = "exportReplaceRule.json")

interface ReplaceManagementRepository {
    fun rows(filter: ReplaceManagementFilter): Flow<List<ReplaceManagementRow>>

    fun groups(): Flow<List<String>>

    suspend fun enabled(ids: List<Long>, value: Boolean)

    suspend fun group(ids: List<Long>, value: String, add: Boolean)

    suspend fun edge(ids: List<Long>, top: Boolean)

    suspend fun move(id: Long, target: Long, after: Boolean)

    suspend fun delete(ids: List<Long>)

    suspend fun export(ids: List<Long>): ReplaceManagementExport

    suspend fun releaseExport(path: String)

    suspend fun importHistory(): List<String>

    suspend fun rememberImport(value: String)

    suspend fun forgetImport(value: String)

    suspend fun manual(): Boolean

    suspend fun manual(value: Boolean)

    suspend fun refreshPipeline()
}

internal fun replaceManagementRow(rule: ReplaceRule) =
    ReplaceManagementRow(
        rule.id,
        rule.name,
        rule.getDisplayNameGroup(),
        rule.group,
        rule.isEnabled,
        rule.order,
    )

/**
 * Preserve original ordering and preview JSON while applying narrow edits to current Room entities.
 */
class AppReplaceManagementRepository(
    private val database: AppDatabase = appDb,
    private val exportDirectory: File = File(appCtx.filesDir, "replace-management-export"),
    private val removeSample: (Long) -> Unit = ReplacePreviewConfig::removeSample,
    private val afterExport: (File) -> Unit = {},
) : ReplaceManagementRepository {
    private val dao
        get() = database.replaceRuleDao

    override fun rows(filter: ReplaceManagementFilter): Flow<List<ReplaceManagementRow>> =
        when (filter) {
                ReplaceManagementFilter.All -> dao.flowAll()
                ReplaceManagementFilter.Enabled -> dao.flowEnabled()
                ReplaceManagementFilter.Disabled -> dao.flowDisabled()
                ReplaceManagementFilter.NoGroup -> dao.flowNoGroup()
                is ReplaceManagementFilter.Group -> dao.flowGroupSearch(filter.name)
                is ReplaceManagementFilter.Search -> dao.flowSearch("%${filter.query}%")
            }
            .map { values -> values.map(::replaceManagementRow) }
            .flowOn(Dispatchers.IO)

    override fun groups() = dao.flowGroups()

    private fun current(ids: List<Long>): List<ReplaceRule> {
        val values = dao.findByIds(*ids.distinct().toLongArray()).associateBy { it.id }
        return ids.distinct().mapNotNull(values::get)
    }

    override suspend fun enabled(ids: List<Long>, value: Boolean): Unit =
        withContext(Dispatchers.IO) {
            database.withTransaction { ids.distinct().forEach { dao.enable(it, value) } }
        }

    override suspend fun group(ids: List<Long>, value: String, add: Boolean): Unit =
        withContext(Dispatchers.IO) {
            if (value.isEmpty()) return@withContext
            database.withTransaction {
                val values =
                    current(ids).map { rule ->
                        rule.copy().let { if (add) it.addGroup(value) else it.removeGroup(value) }
                    }
                if (values.isNotEmpty()) dao.update(*values.toTypedArray())
            }
        }

    override suspend fun edge(ids: List<Long>, top: Boolean): Unit =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val values = current(ids)
                if (values.isEmpty()) return@withTransaction
                val boundary = if (top) dao.minOrder - values.size else dao.maxOrder + 1
                dao.update(
                    *values
                        .mapIndexed { index, rule -> rule.copy(order = boundary + index) }
                        .toTypedArray()
                )
            }
        }

    override suspend fun move(id: Long, target: Long, after: Boolean): Unit =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val values = dao.all
                val reordered = moveRelativeTo(values, id, target, after) { it.id }
                if (reordered != values)
                    dao.update(
                        *reordered
                            .mapIndexed { index, rule -> rule.copy(order = index) }
                            .toTypedArray()
                    )
            }
        }

    override suspend fun delete(ids: List<Long>): Unit =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                database.withTransaction {
                    current(ids).takeIf { it.isNotEmpty() }?.let { dao.delete(*it.toTypedArray()) }
                }
                // Accepted deletion and its local-only sample cleanup finish together, even on
                // owner exit.
                ids.distinct().forEach(removeSample)
            }
        }

    override suspend fun export(ids: List<Long>): ReplaceManagementExport {
        var created: File? = null
        try {
            val output =
                withContext(Dispatchers.IO) {
                    val rules = database.withTransaction { current(ids).map { it.copy() } }
                    check(exportDirectory.isDirectory || exportDirectory.mkdirs())
                    val file = File(exportDirectory, "${UUID.randomUUID()}.json")
                    created = file
                    file.writeText(GSON.toJson(ReplacePreviewConfig.withSamples(rules)))
                    currentCoroutineContext().ensureActive()
                    afterExport(file)
                    ReplaceManagementExport(file.canonicalPath)
                }
            created = null
            return output
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

    override suspend fun manual(): Boolean =
        withContext(Dispatchers.IO) { AppConfig.manualReplaceRule }

    override suspend fun manual(value: Boolean): Unit =
        withContext(Dispatchers.IO) { AppConfig.manualReplaceRule = value }

    override suspend fun refreshPipeline(): Unit =
        withContext(Dispatchers.IO) { ContentProcessor.upReplaceRules() }

    private companion object {
        const val IMPORT_HISTORY = "replaceRuleRecordKey"
        val historyGate = Mutex()
    }
}
