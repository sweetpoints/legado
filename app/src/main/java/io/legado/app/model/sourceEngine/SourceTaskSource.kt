package io.legado.app.model.sourceEngine

import io.legado.app.data.entities.BaseSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Actual App source attached by the submitting facade, never reconstructed from script JSON. */
class SourceTaskSource(val source: BaseSource, val engineSourceId: String) :
    AbstractCoroutineContextElement(Key) {
    init {
        require(engineSourceId.isNotBlank()) { "Source task identity must not be empty" }
    }

    companion object Key : CoroutineContext.Key<SourceTaskSource>

    fun sourceForTask(registeredSourceId: String): BaseSource {
        require(engineSourceId == registeredSourceId) { "Source task context identity mismatch" }
        return source
    }
}

/** Explicit source-less calls mask an inherited actor without manufacturing a source. */
class SourceTaskSourceSuppression(val suppressed: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceTaskSourceSuppression>
}

/** Optional caller diagnostic observes method labels only, never request arguments or responses. */
class SourceTaskHttpObserver(val onCall: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SourceTaskHttpObserver>
}
