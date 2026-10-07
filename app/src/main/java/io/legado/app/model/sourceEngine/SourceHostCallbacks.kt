package io.legado.app.model.sourceEngine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** JSON-only callbacks scoped to the coroutine submitting one engine task. */
class SourceHostCallbacks(val call: suspend (method: String, arguments: List<Any?>) -> Any?) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceHostCallbacks>
}
