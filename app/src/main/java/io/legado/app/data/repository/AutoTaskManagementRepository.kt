package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.model.AutoTask
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.mergeFilteredOrder
import io.legado.app.utils.splitNotBlank
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AutoTaskListItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val cron: String?,
    val summary: String,
    val hasLogin: Boolean,
    val log: String?,
)

data class AutoTaskExportTicket(val id: String, val name: String)

data class AutoTaskExportNotice(val url: String, val summary: String, val passphrase: String?)

data class AutoTaskOnlineDraft(val text: String, val revision: Long)

interface AutoTaskManagementRepository {
    fun observe(): Flow<List<AutoTaskListItem>>

    suspend fun enabled(ids: List<String>, value: Boolean)

    suspend fun cron(ids: List<String>, value: String)

    suspend fun delete(ids: List<String>)

    suspend fun reorder(ids: List<String>)

    suspend fun clearLog(id: String)

    suspend fun history(): List<String>

    suspend fun remember(url: String)

    suspend fun removeHistory(url: String)

    suspend fun export(ids: List<String>?): AutoTaskExportTicket

    suspend fun exportText(ticket: AutoTaskExportTicket): String

    suspend fun releaseExport(ticket: AutoTaskExportTicket)

    suspend fun exportNotice(url: String): AutoTaskExportNotice

    suspend fun readDraft(session: String): AutoTaskOnlineDraft?

    suspend fun writeDraft(session: String, draft: AutoTaskOnlineDraft)
}

class RoomAutoTaskManagementRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val initialize: () -> Unit = { if (database === appDb) AutoTask.all() },
    refresh: (() -> Unit)? = null,
    private val readHistory: () -> List<String> = {
        ACache.get(cacheDir = false)
            .getAsString("autoTaskRecordKey")
            ?.splitNotBlank(",")
            ?.toList()
            .orEmpty()
    },
    private val writeHistory: (List<String>) -> Unit = {
        ACache.get(cacheDir = false).put("autoTaskRecordKey", it.joinToString(","))
    },
    directory: File = File(context.applicationContext.filesDir, "auto-task-management"),
) : AutoTaskManagementRepository {
    private val context = context.applicationContext
    private val scheduleRefresh =
        refresh
            ?: {
                AutoTaskScheduler.refresh(this.context)
                Unit
            }
    private val directory = directory

    override fun observe() = flow {
        initialize()
        emitAll(
            database.autoTaskRuleDao.flowAll().map { rules ->
                rules.map { rule ->
                    val summary = buildString {
                        append(rule.cron.orEmpty())
                        when {
                            !rule.lastError.isNullOrBlank() -> append(" | ").append(rule.lastError)
                            rule.lastRunAt > 0 ->
                                append(" | ").append(AppConst.dateFormat.format(rule.lastRunAt))
                        }
                    }
                    AutoTaskListItem(
                        rule.id,
                        rule.name,
                        rule.enable,
                        rule.cron,
                        summary,
                        AutoTask.buildSource(rule).hasLogin(),
                        rule.lastLog ?: rule.lastError ?: rule.lastResult,
                    )
                }
            }
        )
    }
        .flowOn(Dispatchers.IO)

    private suspend fun mutation(action: () -> Boolean) =
        withContext(Dispatchers.IO + NonCancellable) {
            val changed =
                synchronized(AutoTask) {
                    initialize()
                    var changed = false
                    database.runInTransaction { changed = action() }
                    changed
                }
            if (changed) scheduleRefresh()
        }

    override suspend fun enabled(ids: List<String>, value: Boolean) = mutation {
        ids.distinct().chunked(900).sumOf { database.autoTaskRuleDao.updateEnabled(it, value) } > 0
    }

    override suspend fun cron(ids: List<String>, value: String) = mutation {
        ids.distinct().chunked(900).sumOf { database.autoTaskRuleDao.updateCron(it, value) } > 0
    }

    override suspend fun delete(ids: List<String>) = mutation {
        val present = ids.distinct().filter { database.autoTaskRuleDao.getById(it) != null }
        present.chunked(900).forEach(database.autoTaskRuleDao::deleteByIds)
        present.isNotEmpty()
    }

    override suspend fun reorder(ids: List<String>) = mutation {
        val all = database.autoTaskRuleDao.all()
        val byId = all.associateBy { it.id }
        val reordered = mergeFilteredOrder(all, ids.distinct().mapNotNull(byId::get)) { it.id }
        if (all.map { it.id } == reordered.map { it.id }) false
        else {
            database.autoTaskRuleDao.update(
                *reordered
                    .mapIndexed { index, rule -> rule.copy(customOrder = index) }
                    .toTypedArray()
            )
            true
        }
    }

    override suspend fun clearLog(id: String) =
        withContext(Dispatchers.IO + NonCancellable) {
            synchronized(AutoTask) { database.autoTaskRuleDao.clearRunLog(id) }
            Unit
        }

    override suspend fun history(): List<String> =
        withContext(Dispatchers.IO) { historyLock.withLock { readHistory().toList() } }

    override suspend fun remember(url: String) =
        withContext(Dispatchers.IO) {
            if (url.isAbsUrl())
                historyLock.withLock {
                    val old = readHistory()
                    if (url !in old) writeHistory(listOf(url) + old)
                }
            Unit
        }

    override suspend fun removeHistory(url: String) =
        withContext(Dispatchers.IO) {
            historyLock.withLock { writeHistory(readHistory().filterNot { it == url }) }
            Unit
        }

    private fun file(id: String, suffix: String): File {
        UUID.fromString(id)
        check(directory.isDirectory || directory.mkdirs()) { "无法保存自动任务数据" }
        return File(directory, "$id.$suffix")
    }

    override suspend fun export(ids: List<String>?): AutoTaskExportTicket =
        withContext(Dispatchers.IO + NonCancellable) {
            initialize()
            val selected = ids?.toSet()
            val rules =
                database.autoTaskRuleDao.all().let { all ->
                    if (selected == null) all else all.filter { it.id in selected }
                }
            val ticket =
                AutoTaskExportTicket(
                    UUID.randomUUID().toString(),
                    if (ids == null) "exportAutoTask.json" else "exportAutoTaskSelection.json",
                )
            file(ticket.id, "export").writeText(AutoTask.exportJson(rules))
            ticket
        }

    override suspend fun exportText(ticket: AutoTaskExportTicket): String =
        withContext(Dispatchers.IO) { file(ticket.id, "export").readText() }

    override suspend fun releaseExport(ticket: AutoTaskExportTicket) =
        withContext(Dispatchers.IO + NonCancellable) {
            file(ticket.id, "export").delete()
            Unit
        }

    override suspend fun exportNotice(url: String) =
        withContext(Dispatchers.IO) {
            AutoTaskExportNotice(
                url,
                if (url.isAbsUrl()) DirectLinkUpload.getSummary() else "",
                if (url.isAbsUrl())
                    SourceSharePassphrase.encode(
                        url,
                        SourceSharePassphrase.Type.AUTO_TASK,
                        DirectLinkUpload.getExpiryDate(),
                    )
                else null,
            )
        }

    private fun draftLock(session: String) =
        draftLocks.getOrPut(file(session, "draft").absolutePath) { Mutex() }

    private fun readDraftFile(session: String): AutoTaskOnlineDraft? {
        val input =
            try {
                AtomicFile(file(session, "draft")).openRead()
            } catch (_: java.io.FileNotFoundException) {
                return null
            }
        return GSON.fromJsonObject<AutoTaskOnlineDraft>(
                input.bufferedReader().use { it.readText() }
            )
            .getOrThrow()
    }

    override suspend fun readDraft(session: String) =
        withContext(Dispatchers.IO) { draftLock(session).withLock { readDraftFile(session) } }

    override suspend fun writeDraft(session: String, draft: AutoTaskOnlineDraft) =
        withContext(Dispatchers.IO + NonCancellable) {
            draftLock(session).withLock {
                if ((readDraftFile(session)?.revision ?: -1) > draft.revision) return@withLock
                val target = AtomicFile(file(session, "draft"))
                val output = target.startWrite()
                try {
                    output.write(GSON.toJson(draft).toByteArray())
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
            }
        }

    companion object {
        private val historyLock = Mutex()
        private val draftLocks = ConcurrentHashMap<String, Mutex>()
    }
}
