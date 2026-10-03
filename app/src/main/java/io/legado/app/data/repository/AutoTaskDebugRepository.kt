package io.legado.app.data.repository

import android.content.Context
import android.util.AtomicFile
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.model.AutoTask
import io.legado.app.model.AutoTaskRunner
import io.legado.app.model.Debug
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Execution crosses the data/service boundary as an immutable snapshot. */
data class AutoTaskDebugSnapshot(val id: String, val source: String, val json: String)

data class AutoTaskDebugResult(val log: String)

data class AutoTaskDebugRecord(
    val taskId: String,
    val output: String = "",
    val hasRun: Boolean = false,
    val running: Boolean = false,
    val revision: Long = 0,
)

interface AutoTaskDebugLease {
    suspend fun run(): AutoTaskDebugResult

    /** Idempotent and scoped to this callback owner. */
    fun close()
}

interface AutoTaskDebugRepository {
    suspend fun load(id: String): AutoTaskDebugSnapshot?

    suspend fun acquire(task: AutoTaskDebugSnapshot, log: (String) -> Unit): AutoTaskDebugLease?

    suspend fun read(session: String): AutoTaskDebugRecord?

    suspend fun write(session: String, record: AutoTaskDebugRecord)
}

class AppAutoTaskDebugRepository(
    context: Context,
    private val database: AppDatabase = appDb,
    private val initialize: () -> Unit = { if (database === appDb) AutoTask.all() },
    directory: File = File(context.applicationContext.filesDir, "auto-task-debug"),
) : AutoTaskDebugRepository {
    private val context = context.applicationContext
    private val directory = directory

    override suspend fun load(id: String) =
        withContext(Dispatchers.IO) {
            synchronized(AutoTask) {
                initialize()
                database.autoTaskRuleDao.getById(id)?.let {
                    AutoTaskDebugSnapshot(
                        it.id,
                        AutoTask.buildSource(it).bookSourceUrl,
                        GSON.toJson(it),
                    )
                }
            }
        }

    override suspend fun acquire(
        task: AutoTaskDebugSnapshot,
        log: (String) -> Unit,
    ): AutoTaskDebugLease? =
        withContext(Dispatchers.IO + NonCancellable) {
            val released = AtomicBoolean(false)
            val callback =
                object : Debug.Callback {
                    override fun printLog(state: Int, msg: String) {
                        if (!released.get()) log(msg)
                    }
                }
            if (!Debug.startSimpleDebug(callback, task.source)) return@withContext null
            object : AutoTaskDebugLease {
                private val started = AtomicBoolean(false)

                override suspend fun run(): AutoTaskDebugResult =
                    withContext(Dispatchers.IO) {
                        check(!released.get() && started.compareAndSet(false, true)) {
                            "Debug lease is not active"
                        }
                        val rule = GSON.fromJsonObject<AutoTaskRule>(task.json).getOrThrow()
                        require(
                            rule.id == task.id &&
                                AutoTask.buildSource(rule).bookSourceUrl == task.source
                        )
                        AutoTaskDebugResult(
                            AutoTaskRunner.runTask(context, rule, persist = false).log
                        )
                    }

                override fun close() {
                    if (released.compareAndSet(false, true)) Debug.cancelDebug(callback)
                }
            }
        }

    private fun file(session: String): AtomicFile {
        require(runCatching { UUID.fromString(session) }.isSuccess)
        return AtomicFile(File(directory, "$session.json"))
    }

    private fun readFile(session: String): AutoTaskDebugRecord? {
        val file = file(session)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().bufferedReader().use {
            GSON.fromJsonObject<AutoTaskDebugRecord>(it.readText()).getOrThrow()
        }
    }

    override suspend fun read(session: String) =
        withContext(Dispatchers.IO) { lock(session).withLock { readFile(session) } }

    override suspend fun write(session: String, record: AutoTaskDebugRecord) =
        withContext(Dispatchers.IO + NonCancellable) {
            lock(session).withLock {
                if ((readFile(session)?.revision ?: -1) <= record.revision) {
                    directory.mkdirs()
                    val file = file(session)
                    val output = file.startWrite()
                    try {
                        output.write(
                            GSON.toJson(record.copy(output = record.output.takeLast(20_000)))
                                .toByteArray()
                        )
                        file.finishWrite(output)
                    } catch (error: Throwable) {
                        file.failWrite(output)
                        throw error
                    }
                }
            }
        }

    private fun lock(session: String) =
        locks.getOrPut(File(directory, session).absolutePath) { Mutex() }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
