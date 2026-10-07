package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.nodes.Node
import org.seimicrawler.xpath.JXNode

/** Exact legacy parser, with JSON transport and variables owned by one registered task. */
class LegacyRuleHost(
    private val trustedSourceId: String,
    private val context: CoroutineContext = EmptyCoroutineContext,
) {
    private val values = TaskVariables()

    @Synchronized
    fun evaluate(payload: Map<String, Any?>): Map<String, Any?> {
        context.ensureActive()
        BookSourceScriptBridge.jsonBindings(mapOf("payload" to payload))
        val rule = payload["rule"] as? String ?: invalid("rule must be a string")
        if (rule.isNotBlank() && !supportsRule(rule)) {
            throw SourceScriptException(
                "nested_script_requires_migration",
                "Legacy rule callbacks cannot recursively execute JavaScript",
            )
        }
        val mode = payload["mode"] as? String ?: invalid("mode is required")
        require(mode in setOf("elements", "scalar", "list")) { "Invalid legacy rule mode" }
        val input = payload["input"] ?: invalid("input must not be null")
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
            values.variableMap.putIfAbsent(key, valueText(value))
        }
        val parser =
            AnalyzeRule(ruleData = values, source = source, isFromBookInfo = operation == "info")
                .setCoroutineContext(context)
                .setContent(input, baseUrl)
        if (baseUrl.isNotBlank()) parser.setRedirectUrl(baseUrl)
        // A synchronous Java extraction callback cannot re-enter its active V8 owner.
        val result = parser.withScriptCallback {
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
        context.ensureActive()
        return mapOf("value" to jsonValue(result), "variables" to values.writes())
    }

    private class TaskVariables : RuleDataInterface {
        override val variableMap = hashMapOf<String, String>()
        private val changed = linkedSetOf<String>()

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

    companion object {
        /**
         * Parser capability only; it does not enable contentBatch/callback/source pipeline hooks.
         */
        fun supportsRule(rule: String): Boolean {
            if (rule.isBlank() || rule.contains("{{")) return false
            return runCatching {
                    val parts = AnalyzeRule().splitSourceRule(rule, allInOne = true)
                    parts.isNotEmpty() &&
                        parts.all {
                            it.mode != AnalyzeRule.Mode.Js &&
                                it.mode != AnalyzeRule.Mode.WebJs &&
                                it.putMap.values.all(::supportsRule)
                        }
                }
                .getOrDefault(false)
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

        private fun jsonValue(value: Any?): Any? {
            val visiting = IdentityHashMap<Any, Boolean>()
            fun convert(item: Any?, depth: Int): Any? {
                if (depth > 64) invalid("Legacy rule result is too deeply nested")
                if (item == null || item is String || item is Boolean) return item
                if (item is Number) {
                    require(item.toDouble().isFinite()) { "Non-finite legacy result" }
                    return item
                }
                if (item is Node) return item.outerHtml()
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
    }
}
