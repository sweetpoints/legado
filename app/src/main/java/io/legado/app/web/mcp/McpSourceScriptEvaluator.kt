package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.sourceEngine.BookSourceScriptBridge
import io.legado.app.model.sourceEngine.DartSourceEngine
import io.legado.app.model.sourceEngine.V8ScriptExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Keeps the caller Job attached to V8 requests, so MCP timeouts reach native cancellation. */
internal object McpSourceScriptEvaluator {
    private val json = GsonBuilder().disableHtmlEscaping().create()
    // Source metadata must never replace a host API, including future DTO additions.
    private val hostApiNames =
        setOf(
            "call",
            "net",
            "http",
            "browser",
            "cookies",
            "variables",
            "encoding",
            "crypto",
            "parse",
            "storage",
            "debug",
            "__proto__",
            "prototype",
            "constructor",
        )

    suspend fun evaluate(
        source: BaseSource?,
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        evaluator: suspend (BookSource, String, Map<String, Any?>) -> Any? =
            DartSourceEngine::evaluate,
    ): String? =
        withContext(Dispatchers.IO) {
            val bookSource = source as? BookSource ?: source?.getSource() as? BookSource
            if (bookSource != null) {
                val values =
                    BookSourceScriptBridge.jsonBindings(
                        bindings +
                            ("__mcpSource" to
                                DartSourceEngine.jsonObject(bookSource).filterKeys {
                                    it !in hostApiNames
                                })
                    )
                val wrapped =
                    "(async(__mcpEngineSource)=>{const source=Object.assign(Object.create(__mcpEngineSource),__mcpSource);const sourceApi=source;return await eval(${json.toJson(script)});})(source)"
                val value = evaluator(bookSource, wrapped, values)
                when (value) {
                    null -> null
                    is String -> value
                    else -> json.toJson(value)
                }
            } else {
                val values = BookSourceScriptBridge.jsonBindings(bindings)
                val raw =
                    if (source == null) {
                        V8ScriptExecutor.evaluate(script, values)
                    } else {
                        runInterruptible {
                            source.evalJS(script) {
                                values.forEach { (key, value) -> put(key, value) }
                            }
                        }
                    }
                JsSourceEngine.normalizeJsResult(raw)
            }
        }
}
