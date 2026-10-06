package io.legado.app.model.sourceEngine

import android.os.Looper
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.RegexJsExtensions
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Pure JSON boundary for application scripts executed by the embedded Dart/V8 host. */
object V8ScriptExecutor {
    suspend fun evaluate(
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 10_000,
        sourceId: String? = null,
        prelude: String? = null,
        source: BaseSource? = null,
    ): Any? {
        require(timeoutMillis > 0) { "Script timeout must be positive" }
        return withTimeout(timeoutMillis) {
            DartSourceEngine.evaluateAuxiliary(
                script,
                bindings,
                sourceId = sourceId,
                prelude = prelude,
                timeoutMs = timeoutMillis,
                source = source,
            )
        }
    }

    suspend fun evaluateString(
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        timeoutMillis: Long = 10_000,
        sourceId: String? = null,
        prelude: String? = null,
        source: BaseSource? = null,
    ): String =
        evaluate(script, bindings, timeoutMillis, sourceId, prelude, source)?.toString() ?: "null"

    /** Blocking reader operations run off the UI thread and retain their caller cancellation. */
    fun evaluateBlocking(
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        timeoutMillis: Long = 10_000,
        sourceId: String? = null,
        prelude: String? = null,
        source: BaseSource? = null,
    ): Any? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "V8 scripts must be awaited on the main thread"
        }
        return runBlocking(coroutineContext + Dispatchers.IO) {
            evaluate(script, bindings, timeoutMillis, sourceId, prelude, source)
        }
    }

    /** Replacement helpers are explicit per-task callbacks, never JVM objects in V8. */
    suspend fun evaluateReplacement(
        script: String,
        bindings: Map<String, Any?>,
        extensions: RegexJsExtensions,
        timeoutMillis: Long = 10_000,
    ): String =
        withContext(
            SourceHostCallbacks { method, arguments ->
                fun text(index: Int) = arguments.getOrNull(index)?.toString() ?: "null"
                when (method) {
                    "replacement.log" -> extensions.log(arguments.firstOrNull())
                    "replacement.logType" -> {
                        extensions.log(text(0))
                        null
                    }
                    "replacement.t2s" -> extensions.t2s(text(0))
                    "replacement.s2t" -> extensions.s2t(text(0))
                    "replacement.get" -> extensions.get(text(0))
                    "replacement.put" -> extensions.put(text(0), text(1))
                    else -> error("Unsupported replacement callback: $method")
                }
            }
        ) {
            evaluateString(
                """
                var previousJava = globalThis.java;
                var java = new Proxy(Object.create(null), {
                    get: (_, name) => ['log','logType','t2s','s2t','get','put'].includes(String(name))
                        ? (...args) => __sourceHostSync('replacement.' + String(name),
                            name === 'logType' ? [typeof args[0]] : args)
                        : previousJava && previousJava[name]
                });
                String(eval(replacementScript));
                """
                    .trimIndent(),
                bindings + ("replacementScript" to script),
                timeoutMillis,
            )
        }

    suspend fun checkSyntax(script: String): V8ScriptDiagnostic? =
        DartSourceEngine.checkAuxiliarySyntax(script)
}

/** V8 reports one-based source positions; editor callers convert them only at selection time. */
data class V8ScriptDiagnostic(
    val message: String,
    val lineNumber: Int,
    val columnNumber: Int,
)
