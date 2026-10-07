package io.legado.app.model.sourceEngine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A request-local evaluator for the existing AnalyzeUrl stack; no script is executed here. */
class LegacyUrlScriptContext(
    private val evaluate: (String, Map<String, Any?>, SourceHostCallbacks, CoroutineContext) -> Any?
) : AbstractCoroutineContextElement(Key) {
    fun evaluator(
        context: CoroutineContext
    ): (String, Map<String, Any?>, SourceHostCallbacks) -> Any? = { script, bindings, callbacks ->
        evaluate(script, bindings, callbacks, context)
    }

    companion object Key : CoroutineContext.Key<LegacyUrlScriptContext>
}
