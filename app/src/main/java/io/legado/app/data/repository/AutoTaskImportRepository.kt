package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.model.AutoTask
import io.legado.app.model.prepareImportedAutoTasks
import io.legado.app.service.AutoTaskScheduler
import io.legado.app.utils.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AutoTaskImportStatus {
    New,
    Update,
    Exists,
}

data class AutoTaskImportItem(
    val key: String,
    val id: String,
    val name: String,
    val enabled: Boolean,
    val cron: String,
    val comment: String?,
    val json: String,
    val status: AutoTaskImportStatus,
) {
    val selectedByDefault
        get() = status != AutoTaskImportStatus.Exists
}

data class AutoTaskImportSession(
    val items: List<AutoTaskImportItem>,
    val committed: Boolean = false,
)

interface AutoTaskImportRepository {
    suspend fun load(id: String): AutoTaskImportSession

    suspend fun edit(id: String, key: String, json: String): AutoTaskImportItem

    suspend fun commit(id: String, selected: Set<String>)
}

internal fun sameAutoTaskImportConfiguration(left: AutoTaskRule, right: AutoTaskRule): Boolean {
    fun AutoTaskRule.configuration() =
        copy(
            customOrder = 0,
            lastRunAt = 0,
            lastResult = null,
            lastError = null,
            lastLog = null,
        )
    return left.configuration() == right.configuration()
}

/** Sessions keep scripts and the original comparison snapshot outside the saved-state Bundle. */
class FileAutoTaskImportRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val refresh: () -> Unit = { AutoTaskScheduler.refresh(context.applicationContext) },
    private val readSource: suspend (String) -> String = { source ->
        if (source.isAbsUrl()) {
            okHttpClient
                .newCallResponseBody {
                    if (source.endsWith("#requestWithoutUA")) {
                        url(source.substringBeforeLast("#requestWithoutUA"))
                        header(AppConst.UA_NAME, "null")
                    } else url(source)
                }
                .decompressed()
                .text()
        } else source.toUri().readText(context.applicationContext)
    },
) : AutoTaskImportRepository {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "auto-task-import")

    private data class DiskSession(
        val source: String,
        val items: List<AutoTaskImportItem>? = null,
        val locals: List<AutoTaskRule> = emptyList(),
        val committed: Boolean = false,
        val refreshPending: Boolean = false,
    )

    override suspend fun load(id: String): AutoTaskImportSession =
        io(id) {
            pending[id]?.await()
            var session = read(id)
            if (session.items == null) {
                val tasks = parse(session.source.trim())
                require(tasks.isNotEmpty()) { context.getString(R.string.wrong_format) }
                val locals =
                    synchronized(AutoTask) {
                            if (database === appDb) AutoTask.all()
                            else database.autoTaskRuleDao.all()
                        }
                        .map { it.copy() }
                val localMap = locals.associateBy { it.id }
                session =
                    session.copy(
                        items =
                            tasks.mapIndexed { index, task ->
                                item(index.toString(), task, localMap)
                            },
                        locals = locals,
                    )
                write(id, session)
            }
            if (session.refreshPending) {
                refresh()
                session = session.copy(refreshPending = false)
                write(id, session)
            }
            AutoTaskImportSession(session.items.orEmpty(), session.committed)
        }

    override suspend fun edit(id: String, key: String, json: String): AutoTaskImportItem =
        io(id) {
            val session = read(id)
            check(!session.committed)
            require(session.items.orEmpty().any { it.key == key })
            val task = validate(GSON.fromJsonObject<AutoTaskRule>(json).getOrThrow())
            val updated = item(key, task, session.locals.associateBy { it.id })
            write(
                id,
                session.copy(
                    items = session.items.orEmpty().map { if (it.key == key) updated else it }
                ),
            )
            updated
        }

    override suspend fun commit(id: String, selected: Set<String>) =
        withContext(NonCancellable) {
            io(id) {
                var session = read(id)
                check(session.items != null)
                if (!session.committed) {
                    val tasks =
                        session.items
                            .orEmpty()
                            .filter { it.key in selected }
                            .map {
                                validate(GSON.fromJsonObject<AutoTaskRule>(it.json).getOrThrow())
                            }
                    if (tasks.isNotEmpty())
                        synchronized(AutoTask) {
                            val locals =
                                if (database === appDb) AutoTask.all()
                                else database.autoTaskRuleDao.all()
                            val merged = prepareImportedAutoTasks(locals, tasks)
                            database.autoTaskRuleDao.upsert(*merged.toTypedArray())
                        }
                    session = session.copy(committed = true, refreshPending = tasks.isNotEmpty())
                    write(id, session)
                }
                if (session.refreshPending) {
                    refresh()
                    write(id, session.copy(refreshPending = false))
                }
            }
        }

    private suspend fun parse(source: String, depth: Int = 0): List<AutoTaskRule> {
        require(depth < 32) { context.getString(R.string.wrong_format) }
        return when {
            source.isJsonObject() ->
                listOf(validate(GSON.fromJsonObject<AutoTaskRule>(source).getOrThrow()))
            source.isJsonArray() ->
                GSON.fromJsonArray<AutoTaskRule>(source).getOrThrow().map(::validate)
            source.isAbsUrl() || source.isUri() -> parse(readSource(source).trim(), depth + 1)
            else -> error(context.getString(R.string.wrong_format))
        }
    }

    private fun validate(task: AutoTaskRule): AutoTaskRule {
        val normalized =
            task.copy(
                name = task.name.orEmpty().trim(),
                cron = task.cron?.trim().orEmpty().ifBlank { AutoTask.DEFAULT_CRON },
                script = task.script.orEmpty(),
            )
        requireNotNull(normalized.id as String?) { context.getString(R.string.wrong_format) }
        require(normalized.name.isNotBlank()) {
            context.getString(R.string.auto_task_name_required)
        }
        require(CronSchedule.parse(normalized.cron.orEmpty()) != null) {
            context.getString(R.string.auto_task_cron_invalid)
        }
        require(AutoTask.normalizeScript(normalized.script).isNotBlank()) {
            context.getString(R.string.auto_task_script_empty)
        }
        return normalized
    }

    private fun item(
        key: String,
        task: AutoTaskRule,
        locals: Map<String, AutoTaskRule>,
    ): AutoTaskImportItem {
        val local = locals[task.id]
        val status =
            when {
                local == null -> AutoTaskImportStatus.New
                sameAutoTaskImportConfiguration(task, local) -> AutoTaskImportStatus.Exists
                else -> AutoTaskImportStatus.Update
            }
        return AutoTaskImportItem(
            key,
            task.id,
            task.name,
            task.enable,
            task.cron.orEmpty(),
            task.comment,
            GSON.toJson(task),
            status,
        )
    }

    private suspend fun <T> io(id: String, action: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            locks.getOrPut(id) { Mutex() }.withLock { action() }
        }

    private fun read(id: String): DiskSession =
        GSON.fromJsonObject<DiskSession>(
                AtomicFile(File(directory, "$id.json")).openRead().bufferedReader().use {
                    it.readText()
                }
            )
            .getOrThrow()

    private fun write(id: String, session: DiskSession) =
        writeFile(directory, id, GSON.toJson(session))

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pending = ConcurrentHashMap<String, Deferred<Unit>>()
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun stage(context: Context, source: String): String {
            val id = UUID.randomUUID().toString()
            val directory = File(context.applicationContext.filesDir, "auto-task-import")
            val job =
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        writeFile(directory, id, GSON.toJson(DiskSession(source)))
                    } finally {
                        pending.remove(id)
                    }
                }
            pending[id] = job
            job.start()
            return id
        }

        private fun writeFile(directory: File, id: String, json: String) {
            directory.mkdirs()
            val file = AtomicFile(File(directory, "$id.json"))
            val output = file.startWrite()
            try {
                output.write(json.toByteArray())
                file.finishWrite(output)
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
    }
}
