package io.legado.app.web.mcp

import com.google.gson.GsonBuilder
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.model.sourceEngine.BookSourceScriptBridge
import io.legado.app.model.sourceEngine.DartSourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
                // General MCP and RSS retain their own runtime; book-source failures never enter
                // it.
                val context = currentCoroutineContext()
                val raw = runScriptWithContext {
                    if (source == null)
                        RhinoScriptEngine.eval(script) {
                            bindings.forEach { (key, value) -> put(key, value) }
                        }
                    else
                        source.evalJS(script) {
                            bindings.forEach { (key, value) -> put(key, value) }
                        }
                }
                JsSourceEngine.normalizeJsResult(raw, context)
            }
        }
}
