package io.legado.app.model.sourceEngine

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.legado.app.data.appDb
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.VerificationResult
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One engine for the application; Compose observes task state through its ViewModel. */
class FlutterSourceRepository(context: Context) : SourceEngineBackend {
    private val applicationContext = context.applicationContext
    private val lock = Mutex()
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browserJobs = mutableMapOf<String, Job>()
    private val ready = CompletableDeferred<Unit>()
    private val mutableTasks = MutableStateFlow<Map<String, SourceTaskState>>(emptyMap())
    override val tasks: StateFlow<Map<String, SourceTaskState>> = mutableTasks

    private suspend fun ensureStarted() =
        withContext(Dispatchers.Main.immediate) {
            lock.withLock {
                check(!closed) { "Flutter source repository is closed" }
                if (engine == null) {
                    val created = FlutterEngine(applicationContext)
                    val bridge =
                        MethodChannel(created.dartExecutor.binaryMessenger, "legado/source_engine")
                    bridge.setMethodCallHandler { call, result ->
                        when (call.method) {
                            "ready" -> {
                                if (call.argument<Int>("protocolVersion") == 1) ready.complete(Unit)
                                else
                                    ready.completeExceptionally(
                                        IllegalStateException(
                                            "Flutter engine protocol version mismatch"
                                        )
                                    )
                                result.success(null)
                            }
                            "startupError" -> {
                                ready.completeExceptionally(
                                    IllegalStateException(
                                        "${call.argument<String>("code")}: ${call.argument<String>("message")}"
                                    )
                                )
                                result.success(null)
                            }
                            else -> result.notImplemented()
                        }
                    }
                    val platform =
                        MethodChannel(
                            created.dartExecutor.binaryMessenger,
                            "legado/source_host_platform",
                        )
                    platform.setMethodCallHandler { call, result ->
                        if (call.method == "cancelBrowser") {
                            browserJobs.remove(call.argument<String>("taskId"))?.cancel()
                            result.success(null)
                        } else if (call.method != "browser") result.notImplemented()
                        else {
                            val browserTaskId = call.argument<String>("taskId")
                            val job = scope.launch {
                                try {
                                    val sourceId = requireNotNull(call.argument<String>("sourceId"))
                                    val url = requireNotNull(call.argument<String>("url"))
                                    val title = call.argument<String>("title").orEmpty()
                                    val response =
                                        withContext(Dispatchers.IO) {
                                            val source =
                                                appDb.bookSourceDao.getBookSource(sourceId)
                                                    ?: error("Browser source not found")
                                            SourceVerificationHelp.getVerificationResult(
                                                source,
                                                url,
                                                title,
                                                true,
                                                false,
                                                coroutineContext = coroutineContext,
                                            )
                                        }
                                    when (response) {
                                        is VerificationResult.Response ->
                                            result.success(
                                                mapOf(
                                                    "url" to response.value.first,
                                                    "body" to response.value.second,
                                                    "cookie" to CookieStore.getCookie(url),
                                                )
                                            )
                                        VerificationResult.Refetch ->
                                            result.success(
                                                mapOf(
                                                    "url" to url,
                                                    "refetch" to true,
                                                    "cookie" to CookieStore.getCookie(url),
                                                )
                                            )
                                    }
                                } catch (error: Exception) {
                                    result.error("BROWSER_FAILED", error.message, null)
                                } finally {
                                    if (browserTaskId != null) browserJobs.remove(browserTaskId)
                                }
                            }
                            if (browserTaskId != null) browserJobs[browserTaskId] = job
                        }
                    }
                    engine = created
                    channel = bridge
                    try {
                        created.dartExecutor.executeDartEntrypoint(
                            DartExecutor.DartEntrypoint.createDefault()
                        )
                    } catch (error: Exception) {
                        ready.completeExceptionally(error)
                        created.destroy()
                        engine = null
                        channel = null
                        throw error
                    }
                }
            }
            try {
                withTimeout(30_000) { ready.await() }
            } catch (error: TimeoutCancellationException) {
                throw IllegalStateException(
                    "Flutter/V8 engine startup timed out before ready handshake",
                    error,
                )
            }
        }

    override suspend fun execute(
        operation: String,
        sourceJson: String,
        input: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        ensureStarted()
        val taskId = UUID.randomUUID().toString()
        return withContext(Dispatchers.Main.immediate) {
            mutableTasks.value = mutableTasks.value + (taskId to SourceTaskState(taskId, "running"))
            val response = CompletableDeferred<Any?>()
            channel!!.invokeMethod(
                "execute",
                mapOf(
                    "protocolVersion" to 1,
                    "taskId" to taskId,
                    "operation" to operation,
                    "sourceJson" to sourceJson,
                    "input" to input,
                ),
                object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        response.complete(result)
                    }

                    override fun error(code: String, message: String?, details: Any?) {
                        response.completeExceptionally(
                            IllegalStateException("$code: ${message.orEmpty()}")
                        )
                    }

                    override fun notImplemented() {
                        response.completeExceptionally(
                            IllegalStateException("Dart engine protocol unavailable")
                        )
                    }
                },
            )
            try {
                val raw = withTimeout(120_000) { response.await() }
                require(raw is List<*>) { "Invalid engine result: expected list" }
                raw.map { row ->
                    require(row is Map<*, *>) { "Invalid engine result item" }
                    row.entries.associate { (key, value) ->
                        require(key is String)
                        key to value
                    }
                }
            } finally {
                // Cancellation also reaches Dart; completion is idempotent there.
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    channel?.invokeMethod("cancel", mapOf("taskId" to taskId))
                    mutableTasks.value = mutableTasks.value - taskId
                }
            }
        }
    }

    override suspend fun close() =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (closed) return@withContext
            closed = true
            browserJobs.values.forEach { it.cancel() }
            browserJobs.clear()
            try {
                val response = CompletableDeferred<Unit>()
                channel?.invokeMethod(
                    "shutdown",
                    null,
                    object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            response.complete(Unit)
                        }

                        override fun error(code: String, message: String?, details: Any?) {
                            response.completeExceptionally(
                                IllegalStateException("$code: ${message.orEmpty()}")
                            )
                        }

                        override fun notImplemented() {
                            response.complete(Unit)
                        }
                    },
                ) ?: response.complete(Unit)
                withTimeout(15_000) { response.await() }
            } finally {
                channel?.setMethodCallHandler(null)
                engine?.destroy()
                channel = null
                engine = null
                scope.cancel()
                mutableTasks.value = emptyMap()
            }
        }
}
