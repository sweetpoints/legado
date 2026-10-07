package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.GSON
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ensureActive

/** Original URL/request semantics at an outer, task-bound JSON boundary. */
class LegacyRequestHost(
    private val trustedSourceId: String,
    private val context: CoroutineContext = EmptyCoroutineContext,
) {
    private val values = TaskVariables()

    fun resolve(payload: Map<String, Any?>, fromScript: Boolean = true): Map<String, Any?> {
        val analyzed = analyze(payload, fromScript)
        val descriptor = analyzed.resolveRequestDescriptor(boolean(payload, "includeCookies", true))
        context.ensureActive()
        return envelope(descriptor)
    }

    suspend fun fetch(payload: Map<String, Any?>, fromScript: Boolean = true): Map<String, Any?> {
        val analyzed = analyze(payload, fromScript)
        // This is the sole HTTP/WebView path: no compile-then-send duplicate request.
        val response = analyzed.getStrResponseAwait()
        context.ensureActive()
        return envelope(
            mapOf(
                "url" to response.url,
                "body" to response.body.orEmpty(),
                "status" to response.raw.code,
                "headers" to response.raw.headers.toMultimap(),
            )
        )
    }

    private fun envelope(value: Map<String, Any?>): Map<String, Any?> =
        mapOf("value" to value, "variables" to values.writes())

    private fun analyze(payload: Map<String, Any?>, fromScript: Boolean): AnalyzeUrl {
        context.ensureActive()
        if (fromScript) {
            throw SourceScriptException(
                "nested_script_requires_migration",
                "Legacy request resolution requires a trusted outer request",
            )
        }
        BookSourceScriptBridge.jsonBindings(mapOf("payload" to payload))
        val urlRule = payload["urlRule"] as? String ?: invalid("urlRule must be a string")
        val sourceData = payload["source"] as? Map<*, *> ?: invalid("source must be an object")
        val source = GSON.fromJson(GSON.toJson(sourceData), BookSource::class.java)
        source.bookSourceUrl = trustedSourceId
        val variables = objectMap(payload["variables"]).orEmpty()
        variables.forEach { (key, value) -> values.seed(key, valueText(value)) }
        val key = payload["key"] ?: variables["key"]
        require(key == null || key is String) { "Request key must be a string" }
        val infoMap = stringMap(variables["infoMap"])?.toMutableMap()
        return AnalyzeUrl(
            urlRule,
            key = key as? String,
            page = integer(payload["page"] ?: variables["page"]),
            speakText = variables["speakText"] as? String,
            speakSpeed = integer(variables["speakSpeed"]),
            baseUrl = payload["baseUrl"] as? String ?: trustedSourceId,
            source = source,
            ruleData = values,
            coroutineContext = context,
            headerMapF = stringMap(payload["headers"]),
            hasLoginHeader = boolean(payload, "hasLoginHeader", true),
            infoMap = infoMap,
            extraParams = stringMap(payload["extraParams"]),
            scriptBookSnapshot = objectMap(variables["book"]),
            scriptChapterSnapshot = objectMap(variables["chapter"]),
        )
    }

    private class TaskVariables : RuleDataInterface {
        override val variableMap = hashMapOf<String, String>()
        private val changed = linkedSetOf<String>()

        @Synchronized
        fun seed(key: String, value: String) {
            if (key !in changed) variableMap[key] = value
        }

        @Synchronized
        override fun putVariable(key: String, value: String?): Boolean {
            changed += key
            return super.putVariable(key, value)
        }

        @Synchronized override fun getVariable(key: String): String = super.getVariable(key)

        @Synchronized
        override fun putBigVariable(key: String, value: String?) {
            if (value != null) variableMap[key] = value
        }

        override fun getBigVariable(key: String): String? = null

        @Synchronized
        fun writes(): Map<String, String> = changed.associateWith { variableMap[it].orEmpty() }
    }

    companion object {
        private fun objectMap(value: Any?): Map<String, Any?>? {
            if (value == null) return null
            val map = value as? Map<*, *> ?: invalid("Request object expected")
            return map.entries.associate { (key, entry) ->
                require(key is String) { "Request object keys must be strings" }
                key to entry
            }
        }

        private fun stringMap(value: Any?): Map<String, String>? =
            objectMap(value)?.mapValues { (_, item) ->
                item as? String ?: invalid("Request header/parameter values must be strings")
            }

        private fun integer(value: Any?): Int? {
            if (value == null) return null
            val number = value as? Number ?: invalid("Request integer expected")
            val numeric = number.toDouble()
            if (
                !numeric.isFinite() ||
                    numeric != kotlin.math.floor(numeric) ||
                    numeric < Int.MIN_VALUE ||
                    numeric > Int.MAX_VALUE
            ) {
                invalid("Request integer is out of range")
            }
            return number.toInt()
        }

        private fun boolean(payload: Map<String, Any?>, name: String, fallback: Boolean): Boolean =
            if (!payload.containsKey(name)) fallback
            else payload[name] as? Boolean ?: invalid("Request boolean expected")

        private fun valueText(value: Any?): String =
            when (value) {
                null -> ""
                is String -> value
                is Number,
                is Boolean -> value.toString()
                else -> GSON.toJson(value)
            }

        private fun invalid(message: String): Nothing =
            throw SourceScriptException("invalid_request", message)
    }
}
