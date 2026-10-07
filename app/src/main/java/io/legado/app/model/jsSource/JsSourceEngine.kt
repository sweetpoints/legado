package io.legado.app.model.jsSource

import io.legado.app.utils.GSON
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Normalizes the JSON values returned by V8; serialization runs inside V8 before transport. */
object JsSourceEngine {
    fun normalizeJsResult(result: Any?, coroutineContext: CoroutineContext? = null): String? {
        coroutineContext?.ensureActive()
        return when (result) {
            null -> null
            is String -> result
            is CharSequence -> result.toString()
            else -> GSON.toJson(result)
        }
    }
}
