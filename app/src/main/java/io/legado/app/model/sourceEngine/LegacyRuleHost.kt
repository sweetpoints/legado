package io.legado.app.model.sourceEngine

import io.legado.app.constant.AppPattern
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import io.legado.app.utils.HtmlFormatter
import java.net.URL
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.nodes.Node
import org.seimicrawler.xpath.JXNode

/** Exact legacy parser, with JSON transport and variables owned by one registered task. */
class LegacyRuleHost(
    private val trustedSourceId: String,
    private val context: CoroutineContext = EmptyCoroutineContext,
) {
    private val values = TaskVariables()
    private val variableScopes = LegacyVariableScopes()
    private val nodeLock = Any()
    private val nodeTokens = IdentityHashMap<Node, String>()
    private val nodes = hashMapOf<String, Node>()
    private val valueTokens = IdentityHashMap<Any, String>()
    private val storedValues = hashMapOf<String, Any>()
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
                    storedValues.clear()
                    valueTokens.clear()
                    variableScopes.clear()
                    true
                }
            }
        if (dispose) completionHandle?.dispose()
    }

    private fun ensureOpen() {
        if (closed) invalid("Legacy rule task is closed")
    }

    private fun restoreInput(input: Any): Any {
        if (input !is Map<*, *> || input.size != 1) return input
        val key =
            when {
                input.containsKey(NODE_REF) -> NODE_REF
                input.containsKey(VALUE_REF) -> VALUE_REF
                else -> return input
            }
        val token = input[key] as? String ?: invalid("Legacy reference must be a string")
        return synchronized(nodeLock) {
            ensureOpen()
            (if (key == NODE_REF) nodes[token] else storedValues[token])
                ?: invalid("Legacy reference does not belong to this active task")
        }
    }

    private fun valueReference(value: Any): Map<String, String> =
        synchronized(nodeLock) {
            ensureOpen()
            val token =
                valueTokens.getOrPut(value) {
                    UUID.randomUUID().toString().also { storedValues[it] = value }
                }
            mapOf(VALUE_REF to token)
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
    fun evaluate(
        payload: Map<String, Any?>,
        fromScript: Boolean = true,
        allowWebScripts: Boolean = false,
    ): Map<String, Any?> {
        context.ensureActive()
        ensureOpen()
        BookSourceScriptBridge.jsonBindings(mapOf("payload" to payload))
        val rule = payload["rule"] as? String ?: invalid("rule must be a string")
        if (
            rule.isNotBlank() &&
                !supportsRule(
                    rule,
                    allowJs = !fromScript,
                    allowWebScripts = allowWebScripts && !fromScript,
                )
        ) {
            throw SourceScriptException(
                if (fromScript) "nested_script_requires_migration" else "legacy_requires_migration",
                if (fromScript) "Legacy rule callbacks cannot recursively execute JavaScript"
                else "WebView rules require a separate asynchronous platform capability",
            )
        }
        val mode = payload["mode"] as? String ?: invalid("mode is required")
        require(mode in setOf("elements", "element", "content", "scalar", "list")) {
            "Invalid legacy rule mode"
        }
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
        val scoped = variableScopes.select(payload["variableScope"])
        if (scoped == null)
            bindings.forEach { (key, value) ->
                require(key is String) { "Legacy variable names must be strings" }
                values.seed(key, valueText(value))
            }
        val parser =
            AnalyzeRule(
                    ruleData = scoped ?: values,
                    source = source,
                    isFromBookInfo = operation == "info",
                )
                .setCoroutineContext(context)
                .setScriptContextSnapshots(
                    snapshotObject(bindings["book"])?.let {
                        if (scoped == null) it
                        else it + ("variable" to GSON.toJson(scoped.layerVariables("book")))
                    },
                    snapshotObject(bindings["chapter"])?.let {
                        if (scoped == null) it
                        else it + ("variable" to GSON.toJson(scoped.layerVariables("chapter")))
                    },
                )
                .setContent(input, baseUrl)
        if (baseUrl.isNotBlank()) parser.setRedirectUrl(baseUrl)
        val extract = {
            when (mode) {
                "elements" -> parser.getElements(rule)
                "element" -> parser.getElement(rule)
                "content" ->
                    formatContent(
                        parser.getString(parser.splitSourceRule(rule), unescape = false),
                        bindings,
                        source,
                        baseUrl,
                    )
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
        val transported =
            if (mode == "element") {
                if (result == null) invalid("内容不可空（Content cannot be null）")
                valueReference(result)
            } else jsonValue(result)
        return mapOf("value" to transported, "variables" to (scoped?.writes() ?: values.writes())) +
            (scoped?.let { mapOf("variableScope" to it.snapshot()) } ?: emptyMap())
    }

    private fun formatContent(
        raw: String,
        bindings: Map<*, *>,
        source: BookSource,
        baseUrl: String,
    ): String {
        val explicit = bindings["__legacyContentFormat"]
        require(explicit == null || explicit is Boolean) { "Invalid content formatting flag" }
        val bookType = (bindings["book"] as? Map<*, *>)?.get("type") as? Number
        val shouldFormat =
            explicit as? Boolean
                ?: if (bookType != null) {
                    bookType.toInt() and (BookType.audio or BookType.video) == 0
                } else
                    source.bookSourceType != BookSourceType.audio &&
                        source.bookSourceType != BookSourceType.video
        if (!shouldFormat) return raw
        val adapt = bindings["__legacyAdaptSpecialStyle"]
        require(adapt == null || adapt is Boolean) { "Invalid special-style formatting flag" }
        val useHtml = linkedMapOf<String, String>()
        var content = raw
        if (adapt as? Boolean ?: AppConfig.adaptSpecialStyle) {
            content =
                AppPattern.useHtmlRegex.replace(content) {
                    val placeholder = "{usehtml_${useHtml.size}}"
                    useHtml[placeholder] = it.value
                    placeholder
                }
        }
        content = HtmlFormatter.formatKeepImg(content, URL(baseUrl))
        if ('&' in content) content = StringEscapeUtils.unescapeHtml4(content)
        useHtml.forEach { (placeholder, original) ->
            content = content.replace(placeholder, original)
        }
        return content
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
        const val VALUE_REF = "__legacyRuleValueRef"

        /**
         * Parser capability only; it does not enable contentBatch/callback/source pipeline hooks.
         */
        fun supportsRule(
            rule: String,
            allowJs: Boolean = false,
            allowWebScripts: Boolean = false,
        ): Boolean {
            if (rule.isBlank() || (!allowJs && rule.contains("{{"))) return false
            return runCatching {
                    val parts = AnalyzeRule().splitSourceRule(rule, allInOne = true)
                    parts.isNotEmpty() &&
                        parts.all {
                            (allowJs || it.mode != AnalyzeRule.Mode.Js) &&
                                (allowWebScripts || it.mode != AnalyzeRule.Mode.WebJs) &&
                                it.putMap.values.all { nested ->
                                    supportsRule(nested, allowJs, allowWebScripts)
                                }
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
