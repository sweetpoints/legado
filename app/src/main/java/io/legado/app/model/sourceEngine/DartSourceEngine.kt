package io.legado.app.model.sourceEngine

import com.google.gson.reflect.TypeToken
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BaseSource
import io.legado.app.model.SharedJsScope
import io.legado.app.model.sourceEngine.SourceHostCallbacks
import io.legado.app.utils.fromJsonObject
import io.legado.app.constant.AppConst
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.withContext
import java.net.URI
import java.security.SecureRandom
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

    suspend fun evaluateAuxiliary(
        script: String,
        bindings: Map<String, Any?>,
        sourceId: String?,
        sourceJson: Map<String, Any?>?,
        prelude: String?,
        timeoutMs: Long,
    ): Any? = throw UnsupportedOperationException("V8 auxiliary protocol unavailable")

    suspend fun checkAuxiliarySyntax(script: String): V8ScriptDiagnostic? =
        throw UnsupportedOperationException("V8 syntax protocol unavailable")

    suspend fun clearSourceState(sourceId: String) {
        throw UnsupportedOperationException("V8 source-state protocol unavailable")
    }

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
    ): Any? = backend.evaluate(sourceJson(source), script, jsonObject(bindings))

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

    private val secureRandom by lazy { SecureRandom() }

    fun ownerId(source: BaseSource): String {
        val original = source.getSource() ?: source
        return "${original.javaClass.name}:${original.getKey()}"
    }

    suspend fun evaluateAuxiliary(
        script: String,
        bindings: Map<String, Any?> = emptyMap(),
        sourceId: String? = null,
        prelude: String? = null,
        timeoutMs: Long = 10_000,
        source: BaseSource? = null,
    ): Any? {
        require(timeoutMs > 0) { "Script timeout must be positive" }
        val original = source?.let { it.getSource() ?: it }
        val owner = original?.let(::ownerId) ?: sourceId
        require(sourceId == null || original == null || sourceId == owner) { "Auxiliary source identity mismatch" }
        val globals = bindings.toMutableMap()
        require(globals.keys.none { it in setOf("java", "source", "sourceApi", "__sourceHostSync", "globalThis", "cookie", "cache", "global") }) {
            "V8 host bindings cannot be replaced"
        }
        original?.let {
            globals.putIfAbsent("sourceData", jsonObject(it))
        }
        val descriptor = original?.let {
            val definition = (it as? BookSource)?.let { bookSource ->
                GSON.fromJsonObject<Map<String, Any?>>(sourceJson(bookSource)).getOrNull()
                    ?.takeIf { definition -> (definition["version"] as? Number)?.toDouble() == 1.0 }
            }
            val headers = linkedMapOf<String, String>()
            val modernHeaders = definition?.get("headers") as? Map<*, *>
            if (modernHeaders != null) {
                modernHeaders.forEach { (key, value) ->
                    require(key is String && value is String) { "Modern auxiliary headers must be strings" }
                    headers[key] = value
                }
            } else {
                it.header?.let { text -> GSON.fromJsonObject<Map<String, String>>(text).getOrNull()?.let(headers::putAll) }
            }
            if (headers.keys.none { key -> key.equals(AppConst.UA_NAME, ignoreCase = true) }) {
                headers[AppConst.UA_NAME] = AppConfig.userAgent
            }
            it.getLoginHeaderMap()?.let(headers::putAll)
            val base = runCatching { URI(definition?.get("baseUrl") as? String ?: it.getKey()) }.getOrNull()
            buildMap<String, Any?> {
                if (base?.scheme in setOf("http", "https") && !base?.host.isNullOrBlank()) put("baseUrl", base.toString())
                put("headers", headers)
                put("navigationSourceId", it.getKey())
                put("sourceKind", when (it) { is BookSource -> "book"; is RssSource -> "rss"; is HttpTTS -> "tts"; else -> "auxiliary" })
            }
        }
        original?.let { globals.putIfAbsent("baseUrl", descriptor?.get("baseUrl") ?: it.getKey()) }
        val context = currentCoroutineContext()
        val caller = context[SourceHostCallbacks]
        return withContext(SourceHostCallbacks { method, arguments ->
            if (method == "crypto.randomInt32") {
                require(arguments.isEmpty()) { "randomInt32 takes no arguments" }
                secureRandom.nextInt()
            } else {
                check(caller != null) { "Unbound V8 host callback: $method" }
                caller.call(method, arguments)
            }
        }) {
            backend.evaluateAuxiliary(
                script, BookSourceScriptBridge.jsonBindings(globals), owner, descriptor,
                prelude ?: withContext(Dispatchers.IO) { SharedJsScope.resolveLibrary(original?.jsLib, currentCoroutineContext()) }, timeoutMs,
            )
        }
    }

    suspend fun checkAuxiliarySyntax(script: String): V8ScriptDiagnostic? =
        backend.checkAuxiliarySyntax(script)

    suspend fun clearSourceState(owner: BaseSource) = clearSourceState(ownerId(owner))

    suspend fun clearSourceState(owner: String) = backend.clearSourceState(owner)

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
        return backend.execute(operation, sourceJson(source), input)
    }

    internal fun sourceJson(source: BookSource): String {
        val candidate =
            source.bookSourceComment
                .orEmpty()
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("@source:v1 ") }
                ?.removePrefix("@source:v1 ")
        return candidate ?: GSON.toJson(source)
    }

    fun jsonObject(value: Any): Map<String, Any?> =
        GSON.fromJson(
            GSON.toJson(value),
            object : TypeToken<Map<String, Any?>>() {}.type,
        )
}
