package io.legado.app.model.sourceEngine

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.StateFlow
import splitties.init.appCtx

/** App-facing boundary: default builds have no Flutter dependency. */
data class SourceTaskState(val taskId: String, val phase: String, val error: String? = null)

interface SourceEngineBackend {
    val tasks: StateFlow<Map<String, SourceTaskState>>

    suspend fun close()

    suspend fun execute(
        operation: String,
        sourceJson: String,
        input: Map<String, Any?>,
    ): List<Map<String, Any?>>
}

object DartSourceEngine {
    fun selected(source: BookSource): Boolean =
        source.bookSourceComment.orEmpty().lineSequence().any {
            it.trim() == "@engine:dart" || it.trim().startsWith("@source:v1 ")
        }

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
                "Dart engine is selected but this build has no Flutter engine. Build with -PflutterSourceEngine=true and the source_host AAR repository.",
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
