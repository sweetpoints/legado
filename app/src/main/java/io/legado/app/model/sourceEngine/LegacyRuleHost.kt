package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.jsoup.nodes.Node
import org.seimicrawler.xpath.JXNode

/** Exact legacy parser, with JSON transport and variables owned by one registered task. */
class LegacyRuleHost(
    private val trustedSourceId: String,
    private val context: CoroutineContext = EmptyCoroutineContext,
) {
    private val values = TaskVariables()
    private val nodeLock = Any()
    private val nodeTokens = IdentityHashMap<Node, String>()
    private val nodes = hashMapOf<String, Node>()
    @Volatile private var closed = false
    private val completionHandle: DisposableHandle? = context[Job]?.invokeOnCompletion { close() }

    /** Release task references immediately; never wait for an active parser/V8 evaluation lock. */
    fun close() {
        val dispose =
            synchronized(nodeLock) {
                if (closed) false
                else {
                    closed = true
                    nodes.clear()
                    nodeTokens.clear()
                    true
                }
            }
        if (dispose) completionHandle?.dispose()
    }

    private fun ensureOpen() {
        if (closed) invalid("Legacy rule task is closed")
    }

    private fun restoreInput(input: Any): Any {
        if (input !is Map<*, *> || input.size != 1 || !input.containsKey(NODE_REF)) return input
        val token = input[NODE_REF] as? String ?: invalid("Legacy node reference must be a string")
        return synchronized(nodeLock) {
            ensureOpen()
            nodes[token] ?: invalid("Legacy node reference does not belong to this active task")
        }
    }

    private fun nodeReference(node: Node): Map<String, String> =
        synchronized(nodeLock) {
            ensureOpen()
            val token =
                nodeTokens.getOrPut(node) {
                    UUID.randomUUID().toString().also { nodes[it] = node }
                }
            mapOf(NODE_REF to token)
        }

    @Synchronized
    fun evaluate(payload: Map<String, Any?>, fromScript: Boolean = true): Map<String, Any?> {
        context.ensureActive()
        ensureOpen()
        BookSourceScriptBridge.jsonBindings(mapOf("payload" to payload))
        val rule = payload["rule"] as? String ?: invalid("rule must be a string")
        if (rule.isNotBlank() && !supportsRule(rule, allowJs = !fromScript)) {
            throw SourceScriptException(
                if (fromScript) "nested_script_requires_migration" else "legacy_requires_migration",
                if (fromScript) "Legacy rule callbacks cannot recursively execute JavaScript"
                else "WebView rules require a separate asynchronous platform capability",
            )
        }
        val mode = payload["mode"] as? String ?: invalid("mode is required")
        require(mode in setOf("elements", "scalar", "list")) { "Invalid legacy rule mode" }
        val input = restoreInput(payload["input"] ?: invalid("input must not be null"))
        val baseUrl = payload["baseUrl"] as? String ?: invalid("baseUrl must be a string")
        val isUrl = boolean(payload, "isUrl", false)
        val unescape = boolean(payload, "unescape", true)
        val operation = payload["operation"] as? String ?: invalid("operation must be a string")
        val snapshot = payload["source"] as? Map<*, *> ?: invalid("source must be an object")
        val source = GSON.fromJson(GSON.toJson(snapshot), BookSource::class.java)
        // Metadata may describe a migrated source; script-provided identity is never trusted.
        source.bookSourceUrl = trustedSourceId
        val bindings =
            if (payload.containsKey("variables")) {
                payload["variables"] as? Map<*, *> ?: invalid("variables must be an object")
            } else emptyMap<String, Any?>()
        bindings.forEach { (key, value) ->
            require(key is String) { "Legacy variable names must be strings" }
            values.seed(key, valueText(value))
        }
        val parser =
            AnalyzeRule(ruleData = values, source = source, isFromBookInfo = operation == "info")
                .setCoroutineContext(context)
                .setScriptContextSnapshots(
                    snapshotObject(bindings["book"]),
                    snapshotObject(bindings["chapter"]),
                )
                .setContent(input, baseUrl)
        if (baseUrl.isNotBlank()) parser.setRedirectUrl(baseUrl)
        val extract = {
            when (mode) {
                "elements" -> parser.getElements(rule)
                "scalar" ->
                    parser.getString(
                        parser.splitSourceRule(rule),
                        isUrl = isUrl,
                        unescape = unescape,
                    )
                else -> parser.getStringList(rule, isUrl = isUrl).orEmpty()
            }
        }
        // Outer declarative RPC has no active auxiliary VM. Script callbacks do,
        // and must retain the original synchronous extraction re-entry guard.
        val result = if (fromScript) parser.withScriptCallback(extract) else extract()
        context.ensureActive()
        ensureOpen()
        return mapOf("value" to jsonValue(result), "variables" to values.writes())
    }

    private class TaskVariables : RuleDataInterface {
        override val variableMap = hashMapOf<String, String>()
        private val changed = linkedSetOf<String>()

        fun seed(key: String, value: String) {
            if (key !in changed) variableMap[key] = value
        }

        override fun putVariable(key: String, value: String?): Boolean {
            changed += key
            return super.putVariable(key, value)
        }

        override fun putBigVariable(key: String, value: String?) {
            if (value == null) return
            variableMap[key] = value
        }

        override fun getBigVariable(key: String): String? = null

        fun writes(): Map<String, String> = changed.associateWith { variableMap[it].orEmpty() }
    }

    private fun jsonValue(value: Any?): Any? {
        val visiting = IdentityHashMap<Any, Boolean>()
        fun convert(item: Any?, depth: Int): Any? {
            if (depth > 64) invalid("Legacy rule result is too deeply nested")
            if (item == null || item is String || item is Boolean) return item
            if (item is Number) {
                require(item.toDouble().isFinite()) { "Non-finite legacy result" }
                return item
            }
            if (item is Node) return nodeReference(item)
            if (item is JXNode) return convert(item.value(), depth + 1)
            if (visiting.put(item, true) != null) invalid("Cyclic legacy rule result")
            try {
                return when (item) {
                    is List<*> -> item.map { convert(it, depth + 1) }
                    is Array<*> -> item.map { convert(it, depth + 1) }
                    is Map<*, *> ->
                        item.entries.associate { (key, entry) ->
                            require(key is String) { "Legacy result keys must be strings" }
                            key to convert(entry, depth + 1)
                        }
                    else -> invalid("Unsupported legacy result type")
                }
            } finally {
                visiting.remove(item)
            }
        }
        return convert(value, 0)
    }

    companion object {
        const val NODE_REF = "__legacyRuleNodeRef"

        /**
         * Parser capability only; it does not enable contentBatch/callback/source pipeline hooks.
         */
        fun supportsRule(rule: String, allowJs: Boolean = false): Boolean {
            if (rule.isBlank() || (!allowJs && rule.contains("{{"))) return false
            return runCatching {
                    val parts = AnalyzeRule().splitSourceRule(rule, allInOne = true)
                    parts.isNotEmpty() &&
                        parts.all {
                            (allowJs || it.mode != AnalyzeRule.Mode.Js) &&
                                it.mode != AnalyzeRule.Mode.WebJs &&
                                it.putMap.values.all { nested -> supportsRule(nested, allowJs) }
                        }
                }
                .getOrDefault(false)
        }

        private fun snapshotObject(value: Any?): Map<String, Any?>? {
            if (value == null) return null
            val map = value as? Map<*, *> ?: invalid("Book/chapter snapshot must be an object")
            return map.entries.associate { (key, entry) ->
                require(key is String) { "Snapshot keys must be strings" }
                key to entry
            }
        }

        private fun boolean(payload: Map<String, Any?>, key: String, fallback: Boolean): Boolean =
            if (!payload.containsKey(key)) fallback
            else payload[key] as? Boolean ?: invalid("$key must be boolean")

        private fun valueText(value: Any?): String =
            when (value) {
                null -> ""
                is String -> value
                is Boolean,
                is Number -> value.toString()
                else -> GSON.toJson(value)
            }

        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)
    }
}
