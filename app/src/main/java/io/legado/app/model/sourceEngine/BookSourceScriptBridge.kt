package io.legado.app.model.sourceEngine

import com.script.ScriptBindings
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.htmlunit.corejs.javascript.Wrapper
import java.util.IdentityHashMap

class BookSourceBindingsUnsupportedException : NoStackTraceException(
    "engine_bindings_requires_migration: Book-source bindings must contain JSON values",
) {
    val code: String = "engine_bindings_requires_migration"
}

/** Converts explicit caller bindings only; the Dart host supplies its own APIs. */
object BookSourceScriptBridge {
    private val hostNames = setOf("java", "source", "sourceApi", "cookie", "cache", "global", "globalThis")

    fun bindings(configure: ScriptBindings.() -> Unit): Map<String, Any?> {
        val bindings = ScriptBindings().apply(configure)
        val explicit = linkedMapOf<String, Any?>()
        // TopLevel.put forwards ordinary assignments to its isolated globalThis.
        // ScopeObject.ids contains only lexical slots, so enumerate both owners,
        // without traversing the prototype that carries standard JS builtins.
        val ids: List<Any?> = buildList<Any?> {
            addAll(bindings.ids.toList())
            addAll(bindings.globalThis.ids.toList())
        }.distinct()
        for (id in ids) {
            if (id !is String) throw BookSourceBindingsUnsupportedException()
            if (id !in hostNames) explicit[id] = bindings.get(id, bindings)
        }
        return jsonBindings(explicit)
    }

    fun jsonBindings(values: Map<String, Any?>): Map<String, Any?> {
        val visiting = IdentityHashMap<Any, Boolean>()
        fun convert(value: Any?, depth: Int): Any? {
            if (depth > 64) throw BookSourceBindingsUnsupportedException()
            if (value == null || value is String || value is Boolean) return value
            if (value is Byte || value is Short || value is Int || value is Long) return value
            if (value is Float || value is Double) {
                if (!(value as Number).toDouble().isFinite()) throw BookSourceBindingsUnsupportedException()
                return value
            }
            if (visiting.put(value, true) != null) throw BookSourceBindingsUnsupportedException()
            try {
                return when (value) {
                    is Wrapper -> convert(value.unwrap(), depth + 1)
                    is Book, is BookChapter -> convert(DartSourceEngine.jsonObject(value), depth + 1)
                    is Map<*, *> -> value.entries.associate { (key, item) ->
                        if (key !is String) throw BookSourceBindingsUnsupportedException()
                        key to convert(item, depth + 1)
                    }
                    is List<*> -> value.map { convert(it, depth + 1) }
                    else -> throw BookSourceBindingsUnsupportedException()
                }
            } finally {
                visiting.remove(value)
            }
        }
        return values.filterKeys { it !in hostNames }.mapValues { convert(it.value, 0) }
    }

    fun evaluate(
        source: BookSource,
        script: String,
        configure: ScriptBindings.() -> Unit,
        onMainThread: Boolean,
        evaluator: suspend (BookSource, String, Map<String, Any?>) -> Any? = DartSourceEngine::evaluate,
    ): Any? {
        if (onMainThread) BookSourceLegacyEnginePolicy.rejectBookSourceExecution()
        val converted = bindings(configure)
        // runBlocking owns a fresh Job and responds to interruption of its caller.
        return runBlocking(Dispatchers.IO) { evaluator(source, script, converted) }
    }
}
