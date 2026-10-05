package io.legado.app.model.sourceEngine

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.StateFlow
import splitties.init.appCtx

/** App-facing boundary for the embedded Flutter book-source engine. */
data class SourceTaskState(val taskId: String, val phase: String, val error: String? = null)

interface SourceEngineBackend {
    val tasks: StateFlow<Map<String, SourceTaskState>>

    suspend fun close()

    suspend fun migrate(sourceJson: String): SourceMigrationPreview =
        throw UnsupportedOperationException("Book-source migration is unavailable")

    suspend fun evaluate(
        sourceJson: String,
        script: String,
        bindings: Map<String, Any?>,
        ephemeral: Boolean = false,
    ): Any? = throw UnsupportedOperationException("Book-source auxiliary evaluation is unavailable")

    suspend fun execute(
        operation: String,
        sourceJson: String,
        input: Map<String, Any?>,
    ): List<Map<String, Any?>>
}

object DartSourceEngine {
    suspend fun migrate(source: BookSource): SourceMigrationPreview =
        backend.migrate(GSON.toJson(source))

    suspend fun evaluate(
        source: BookSource,
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
    ): Any? = backend.evaluate(GSON.toJson(source), script, jsonObject(bindings))

    suspend fun evaluateConfiguration(script: String): Any? =
        backend.evaluate(
            GSON.toJson(
                mapOf(
                    "bookSourceUrl" to
                        "https://source-import.invalid/${java.util.UUID.randomUUID()}",
                    "bookSourceName" to "Source configuration import",
                )
            ),
            script,
            emptyMap(),
            ephemeral = true,
        )

    private val backend: SourceEngineBackend by lazy {
        try {
            Class.forName(
                    "io.legado.app.model.sourceEngine.FlutterSourceRepository",
                    true,
                    appCtx.classLoader,
                )
                .getConstructor(android.content.Context::class.java)
                .newInstance(appCtx) as SourceEngineBackend
        } catch (error: ClassNotFoundException) {
            throw IllegalStateException(
                "This app requires the Flutter/V8 source engine. Prepare the source_host AAR before building.",
                error,
            )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Flutter source backend initialization failed", error)
        }
    }

    val tasks: StateFlow<Map<String, SourceTaskState>>
        get() = backend.tasks

    suspend fun execute(
        source: BookSource,
        operation: String,
        input: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val candidate =
            source.bookSourceComment
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("@source:v1 ") }
                ?.removePrefix("@source:v1 ")
        return backend.execute(operation, candidate ?: GSON.toJson(source), input)
    }

    fun jsonObject(value: Any): Map<String, Any?> =
        GSON.fromJson(
            GSON.toJson(value),
            object : TypeToken<Map<String, Any?>>() {}.type,
        )
}
