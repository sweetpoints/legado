package io.legado.app.model.sourceEngine

/** Structured V8 failure used by source checks and speech-script error reporting. */
class SourceScriptException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
