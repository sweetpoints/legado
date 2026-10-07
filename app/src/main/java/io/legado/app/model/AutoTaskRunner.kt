package io.legado.app.model

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.help.source.withSourceNavigationContext
import io.legado.app.model.sourceEngine.SourceHostCallbacks
import io.legado.app.utils.stackTraceStr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

object AutoTaskRunner {

    data class Result(
        val success: Boolean,
        val result: String? = null,
        val error: String? = null,
        val log: String,
    )

    suspend fun runDueTasks(context: Context, now: Long = System.currentTimeMillis()) {
        val dueRules = AutoTaskSchedulePolicy.dueRules(AutoTask.enabled(), now)
        for (task in dueRules) {
            currentCoroutineContext().ensureActive()
            runTask(context, task, persist = true)
        }
    }

    suspend fun runTask(
        context: Context,
        task: AutoTaskRule,
        persist: Boolean,
    ): Result {
        currentCoroutineContext().ensureActive()
        val script = AutoTask.normalizeScript(task.script)
        if (script.isBlank()) {
            return failure(task, IllegalArgumentException("Task script is empty"), persist)
        }
        val startedAt = System.currentTimeMillis()
        val source = AutoTask.buildSource(task)
        Debug.log(source.bookSourceUrl, "Running ${task.name}")
        return try {
            val callerContext = currentCoroutineContext()
            val caller = callerContext[SourceHostCallbacks]
            val taskCallbacks = SourceHostCallbacks { method, arguments ->
                if (method == "replacement.log") {
                    require(arguments.size == 1) { "Task logging requires one JSON value" }
                    source.log(arguments.firstOrNull())
                } else {
                    check(caller != null) { "Unbound task host callback: $method" }
                    caller.call(method, arguments)
                }
            }
            val taskSource = source.withSourceNavigationContext(callerContext + taskCallbacks)
            val rawResult = runInterruptible {
                taskSource.evalJS(taskScriptWrapper) { put("__autoTaskScript", script) }
            }
            currentCoroutineContext().ensureActive()
            val actionLogs = mutableListOf<String>()
            val protocolSummary =
                AutoTaskProtocol.handle(rawResult, context, task.name) {
                    actionLogs.add(it)
                    Debug.log(source.bookSourceUrl, it)
                }
            currentCoroutineContext().ensureActive()
            val resultText = rawResult?.toString()?.take(500)
            val finishedAt = System.currentTimeMillis()
            val log =
                AutoTaskLogFormatter.success(
                    finishedAt,
                    finishedAt - startedAt,
                    actionLogs,
                    protocolSummary ?: resultText,
                )
            if (persist) {
                AutoTask.updateRunState(task.id, finishedAt, resultText, null, log)
            }
            AppLog.put("AutoTask[${task.id}] ${task.name} completed")
            Debug.log(source.bookSourceUrl, "Task completed", state = 1000)
            Result(true, resultText, log = log)
        } catch (error: Throwable) {
            error.autoTaskCancellation()?.let { throw it }
            currentCoroutineContext().ensureActive()
            failure(task, error, persist)
        }
    }

    /** Task scripts keep opaque source ownership and scoped logging in the auxiliary V8 route. */
    private val taskScriptWrapper =
        """
        (async function(__taskJava) {
          const previousJava=globalThis.java;
          const previousLog=globalThis.log;
          const log=value=>__sourceHostSync('replacement.log',[value]);
          const java=new Proxy(Object.create(null), {
            get:(_,name)=>name==='log'?log:__taskJava&&__taskJava[name]
          });
          globalThis.java=java;
          globalThis.log=log;
          try {
            return await eval(__autoTaskScript);
          } finally {
            globalThis.java=previousJava;
            if(previousLog===undefined) delete globalThis.log;
            else globalThis.log=previousLog;
          }
        }).call(globalThis,java)
        """
            .trimIndent()

    private fun failure(task: AutoTaskRule, error: Throwable, persist: Boolean): Result {
        val finishedAt = System.currentTimeMillis()
        val message = AutoTaskLogFormatter.trimError(error.localizedMessage ?: error.toString())
        val log = AutoTaskLogFormatter.failure(finishedAt, message, error.stackTraceStr)
        if (persist) {
            AutoTask.updateRunState(task.id, finishedAt, null, message, log)
        }
        AppLog.put("AutoTask[${task.id}] ${task.name} failed: $message", error)
        Debug.log("${AutoTask.SOURCE_KEY}:${task.id}", message, state = -1)
        return Result(false, error = message, log = log)
    }
}

object AutoTaskLogFormatter {

    const val MAX_LENGTH = 4_000
    const val MAX_ERROR_LENGTH = 1_000
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun success(runAt: Long, elapsedMs: Long, actions: List<String>, detail: String?): String {
        return trim(
            buildString {
                append("[OK] ").append(formatTime(runAt))
                append('\n').append("Elapsed: ").append(elapsedMs).append("ms")
                actions.forEach { append('\n').append("- ").append(it) }
                if (!detail.isNullOrBlank() && detail != actions.joinToString(" | ")) {
                    append('\n').append("Result: ").append(detail)
                }
            }
        )
    }

    fun failure(runAt: Long, message: String, stackTrace: String?): String {
        return trim(
            buildString {
                append("[ERROR] ").append(formatTime(runAt))
                append('\n').append(message)
                if (!stackTrace.isNullOrBlank()) append('\n').append(stackTrace)
            }
        )
    }

    fun trim(text: String): String = text.take(MAX_LENGTH)

    fun trimError(text: String): String = text.take(MAX_ERROR_LENGTH)

    private fun formatTime(epochMs: Long): String {
        return timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}

internal fun Throwable.autoTaskCancellation(): CancellationException? {
    return when (this) {
        is CancellationException -> this
        else -> cause as? CancellationException
    }
}
