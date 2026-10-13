package io.legado.app.model.sourceEngine

import io.legado.app.model.analyzeRule.RuleDataInterface

/** Task-local variable layers. The caller carries their JSON state between App tasks. */
internal class LegacyVariableScopes {
    private val scopes = hashMapOf<String, LegacyScopedVariables>()

    @Synchronized
    fun select(raw: Any?): LegacyScopedVariables? {
        if (raw == null) return null
        val scope = raw as? Map<*, *> ?: invalid("variableScope must be an object")
        val id = scope["id"] as? String ?: invalid("variableScope id must be a string")
        if (id.isBlank()) invalid("variableScope id must not be empty")
        val target = scope["target"] as? String ?: invalid("variableScope target is required")
        if (target !in listOf("source", "book", "chapter")) invalid("Invalid variableScope target")
        val values = scopes.getOrPut(id) { LegacyScopedVariables(id, target) }
        if (values.target != target) invalid("variableScope target cannot change")
        for (layer in listOf("source", "book", "chapter")) {
            val map =
                scope[layer] as? Map<*, *> ?: invalid("variableScope $layer must be an object")
            val strings =
                map.entries.associate { (key, value) ->
                    (key as? String ?: invalid("Variable names must be strings")) to
                        (value as? String ?: invalid("Variable values must be strings"))
                }
            values.seed(layer, strings)
        }
        return values
    }

    @Synchronized
    fun clear() {
        scopes.clear()
    }

    private fun invalid(message: String): Nothing =
        throw SourceScriptException("invalid_request", message)
}

internal class LegacyScopedVariables(val id: String, val target: String) : RuleDataInterface {
    private val layers =
        listOf("source", "book", "chapter").associateWith { hashMapOf<String, String>() }
    private val changed = linkedSetOf<String>()
    override val variableMap: HashMap<String, String>
        get() = layers.getValue(target)

    @Synchronized
    fun seed(layer: String, incoming: Map<String, String>) {
        val values = layers.getValue(layer)
        values.keys.removeAll { it !in incoming && (layer != target || it !in changed) }
        incoming.forEach { (key, value) ->
            if (layer != target || key !in changed) values[key] = value
        }
    }

    @Synchronized
    override fun getVariable(key: String): String {
        val order =
            when (target) {
                "chapter" -> listOf("chapter", "book", "source")
                "book" -> listOf("book", "source")
                else -> listOf("source")
            }
        return order
            .firstNotNullOfOrNull { layers.getValue(it)[key]?.takeIf(String::isNotEmpty) }
            .orEmpty()
    }

    @Synchronized
    override fun putVariable(key: String, value: String?): Boolean {
        changed += key
        if (value == null) variableMap.remove(key) else variableMap[key] = value
        return true
    }

    override fun putBigVariable(key: String, value: String?) {
        putVariable(key, value)
    }

    override fun getBigVariable(key: String): String? = null

    @Synchronized
    fun layerVariables(layer: String): Map<String, String> = layers.getValue(layer).toMap()

    @Synchronized
    fun writes(): Map<String, String> = changed.associateWith { variableMap[it].orEmpty() }

    @Synchronized
    fun snapshot(): Map<String, Any?> =
        mapOf(
            "id" to id,
            "target" to target,
            "source" to layers.getValue("source").toMap(),
            "book" to layers.getValue("book").toMap(),
            "chapter" to layers.getValue("chapter").toMap(),
        )
}
