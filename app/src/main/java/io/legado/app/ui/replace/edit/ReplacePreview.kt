package io.legado.app.ui.replace.edit

import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.NoStackTraceException

/** Compatibility entry point for existing callers and engine regression tests. */
object ReplacePreview {
    const val MAX_SAMPLE_LENGTH = io.legado.app.model.replace.ReplacePreview.MAX_SAMPLE_LENGTH
    fun normalizeSample(sample: String) = io.legado.app.model.replace.ReplacePreview.normalizeSample(sample)
    suspend fun apply(rule: ReplaceRule, sample: String): String = compatible {
        io.legado.app.model.replace.ReplacePreview.apply(rule, sample)
    }
    internal suspend fun apply(rule: ReplaceRule, sample: String, nanoTime: () -> Long): String = compatible {
        io.legado.app.model.replace.ReplacePreview.apply(rule, sample, nanoTime)
    }
    private suspend fun compatible(block: suspend () -> String): String = try { block() }
    catch (error: io.legado.app.model.replace.ReplacePreviewException) {
        throw ReplacePreviewException(ReplacePreviewException.Reason.valueOf(error.reason.name))
    }
}

// Keep the nested Reason API: a typealias does not introduce nested classifier names.
internal class ReplacePreviewException(val reason: Reason) : NoStackTraceException(reason.name) {
    enum class Reason { TIMEOUT, CONTEXT_UNAVAILABLE, JS_EVALUATION }
}
