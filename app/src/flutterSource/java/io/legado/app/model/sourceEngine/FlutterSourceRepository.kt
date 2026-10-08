package io.legado.app.model.sourceEngine

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtensions
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.source.VerificationResult
import io.legado.app.help.source.shouldSuppressSourceNavigation
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val browserJobs = mutableMapOf<String, MutableSet<Job>>()

    private data class HostTask(
        val sourceId: String,
        val context: CoroutineContext,
        val sourceKind: String = "book",
        val navigationSourceId: String = sourceId,
    ) {
        val legacyRequests by lazy { LegacyRequestHost(navigationSourceId, context) }
        private val legacyRuleDelegate = lazy { LegacyRuleHost(navigationSourceId, context) }
        private val legacyHttpDelegate = lazy { NativeLegacyHttpContinuationHost(context) }
        @Volatile private var legacyRulesClosed = false
        val legacyRules: LegacyRuleHost
            get() {
                check(!legacyRulesClosed) { "Legacy rule task is closed" }
                val host = legacyRuleDelegate.value
                if (legacyRulesClosed) {
                    host.close()
                    error("Legacy rule task is closed")
                }
                return host
            }
        val legacyHttpContinuations: NativeLegacyHttpContinuationHost
            get() {
                check(!legacyRulesClosed) { "Legacy HTTP task is closed" }
                val host = legacyHttpDelegate.value
                if (legacyRulesClosed) {
                    host.close()
                    error("Legacy HTTP task is closed")
                }
                return host
            }
        fun closeLegacyRules() {
            legacyRulesClosed = true
            if (legacyRuleDelegate.isInitialized()) legacyRuleDelegate.value.close()
            if (legacyHttpDelegate.isInitialized()) legacyHttpDelegate.value.close()
        }
    }

    private val orgJsoup = NativeOrgJsoupHost()
    private val hostTasks = mutableMapOf<String, HostTask>()
    private val responses = mutableMapOf<String, CompletableDeferred<Any?>>()
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
                            hostTasks[call.argument<String>("taskId")]?.closeLegacyRules()
                            browserJobs.remove(call.argument<String>("taskId"))?.forEach {
                                it.cancel()
                            }
                            result.success(null)
                        } else if (call.method == "call") {
                            val taskId = call.argument<String>("taskId")
                            val job =
                                scope.launch(start = CoroutineStart.LAZY) {
                                    try {
                                        val task =
                                            hostTasks[taskId]
                                                ?: error("Source task is no longer active")
                                        require(
                                            call.argument<String>("sourceId") == task.sourceId
                                        ) {
                                            "Source task identity mismatch"
                                        }
                                        task.context.ensureActive()
                                        val method = requireNotNull(call.argument<String>("method"))
                                        val arguments =
                                            requireNotNull(call.argument<List<Any?>>("arguments"))
                                        val origin = call.argument<Any?>("fromScript")
                                        require(origin == null || origin is Boolean) { "Invalid rule callback origin" }
                                        val fromScript = origin as? Boolean ?: true
                                        val value =
                                            withContext(task.context + Dispatchers.IO) {
                                                callHost(task, method, arguments, fromScript)
                                            }
                                        result.success(value)
                                    } catch (error: SourceScriptException) {
                                        val failure = SourceHostFailure.encode(error)
                                        result.error(failure.code, failure.message, failure.details)
                                    } catch (error: Exception) {
                                        val failure = SourceHostFailure.encode(error)
                                        result.error(failure.code, failure.message, failure.details)
                                    } finally {
                                        if (taskId != null)
                                            browserJobs[taskId]?.remove(
                                                currentCoroutineContext()[Job]
                                            )
                                    }
                                }
                            if (taskId != null)
                                browserJobs.getOrPut(taskId) { mutableSetOf() }.add(job)
                            job.start()
                        } else if (call.method != "browser") result.notImplemented()
                        else {
                            val browserTaskId = call.argument<String>("taskId")
                            val job =
                                scope.launch(start = CoroutineStart.LAZY) {
                                    try {
                                        val sourceId =
                                            requireNotNull(call.argument<String>("sourceId"))
                                        val task =
                                            hostTasks[browserTaskId]
                                                ?: error("Source task is no longer active")
                                        require(sourceId == task.sourceId) {
                                            "Source task identity mismatch"
                                        }
                                        task.context.ensureActive()
                                        check(
                                            !shouldSuppressSourceNavigation(
                                                AppConfig.blockSourceNavigation,
                                                task.context,
                                            )
                                        ) {
                                            "Source navigation is suppressed for this operation"
                                        }
                                        val url = requireNotNull(call.argument<String>("url"))
                                        val title = call.argument<String>("title").orEmpty()
                                        val options =
                                            call.argument<Map<String, Any?>>("options").orEmpty()
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
                                                    options["refetchAfterSuccess"] == true,
                                                    html = options["html"] as? String,
                                                    coroutineContext = task.context,
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
                                        if (browserTaskId != null)
                                            browserJobs[browserTaskId]?.remove(
                                                currentCoroutineContext()[Job]
                                            )
                                    }
                                }
                            if (browserTaskId != null)
                                browserJobs.getOrPut(browserTaskId) { mutableSetOf() }.add(job)
                            job.start()
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

    private fun protocolFailure(code: String, message: String?, details: Any? = null): Exception =
        SourceHostFailure.decode(code, message, details)

    private fun sourceIdentity(sourceJson: String): String {
        val source = GSON.fromJson(sourceJson, Map::class.java)
        return (source["id"] ?: source["bookSourceUrl"]) as? String
            ?: error("Source identity missing")
    }

    private suspend fun callHost(
        task: HostTask,
        method: String,
        args: List<Any?>,
        fromScript: Boolean = true,
    ): Any? {
        if (method == "legacyRequest.resolve" || method == "legacyRequest.fetch") {
            require(args.size == 1 && args[0] is Map<*, *>) { "Invalid legacy request callback" }
            val payload = (args[0] as Map<*, *>).entries.associate { (key, value) ->
                require(key is String) { "Legacy request keys must be strings" }
                key to value
            }
            return if (method == "legacyRequest.fetch") task.legacyRequests.fetch(payload, fromScript)
            else task.legacyRequests.resolve(payload, fromScript)
        }
        if (method == "legacyRule.evaluate") {
            require(args.size == 1 && args[0] is Map<*, *>) { "Invalid legacy rule callback" }
            val payload = (args[0] as Map<*, *>).entries.associate { (key, value) ->
                require(key is String) { "Legacy rule payload keys must be strings" }
                key to value
            }
            return task.legacyRules.evaluate(payload, fromScript, allowWebScripts = !fromScript)
        }
        if (method in NativeLegacyCacheHost.methods) {
            return NativeLegacyCacheHost.call(method, args, task.context)
        }
        val callbackMethods = setOf(
            "analyze.get", "analyze.put", "analyze.getString", "analyze.getStringList",
            "analyze.getElements", "analyze.getElement", "crypto.randomInt32",
            "replacement.log", "replacement.logType", "replacement.t2s", "replacement.s2t",
            "replacement.get", "replacement.put", "localBook.putVolume",
            "ui.get", "ui.put", "ui.searchBook", "ui.addBook", "ui.showPhoto", "ui.open",
            "ui.getString", "ui.getStringList", "ui.setContent", "ui.setBaseUrl", "ui.setRedirectUrl",
            "ui.copyText", "ui.upLoginData", "ui.reLoginView", "ui.refreshExplore", "ui.clearTtsCache",
            "sourceState.getLoginInfo", "sourceState.putLoginInfo", "sourceState.getLoginHeader",
            "sourceState.putLoginHeader", "sourceState.getVariable", "sourceState.putVariable", "sourceState.removeLoginInfo",
        )
        val exploreInfoMapMethods = setOf(
            "exploreInfoMap.get", "exploreInfoMap.put", "exploreInfoMap.remove", "exploreInfoMap.set",
            "exploreInfoMap.save", "exploreInfoMap.saveNow", "exploreInfoMap.getNeedSave", "exploreInfoMap.setNeedSave",
            "exploreInfoMap.putAll", "exploreInfoMap.containsKey", "exploreInfoMap.containsValue", "exploreInfoMap.size",
            "exploreInfoMap.isEmpty", "exploreInfoMap.clear", "exploreInfoMap.keys", "exploreInfoMap.values",
            "exploreInfoMap.entries", "exploreInfoMap.sourceUrl",
        )
        if (method in callbackMethods || method in exploreInfoMapMethods) {
            val caller = task.context[SourceHostCallbacks]
                ?: error("Source task has no bound callback for $method")
            return caller.call(method, args)
        }

        if (method == "batch.cacheContent") {
            require(args.size == 3 && args[0] is String && args[2] is String) {
                "Invalid batch content callback"
            }
            return WebBook.saveDartBatchContent(args[0] as String, args[1], args[2] as String)
        }
        if (
            method.startsWith("browser.") &&
                shouldSuppressSourceNavigation(AppConfig.blockSourceNavigation, task.context)
        ) {
            check(method != "browser.open") { "Source navigation is suppressed for this operation" }
            return null
        }
        if (method in LegacyCookieHost.methods) {
            return LegacyCookieHost.call(method, args, task.context)
        }
        if (method == "javaHost.domCall") {
            require(args.size == 3 && args[0] is Map<*, *> && args[1] is String && args[2] is List<*>) {
                "Invalid legacy DOM callback"
            }
            task.context.ensureActive()
            @Suppress("UNCHECKED_CAST")
            return LegacyDomHost.call(args[0] as Map<*, *>, args[1] as String, args[2] as List<Any?>)
        }
        if (method in NativeOrgJsoupHost.methods) {
            val source = if (task.context[SourceTaskSourceSuppression]?.suppressed == true) null
                else task.context[SourceTaskSource]?.sourceForTask(task.sourceId)
            val owner = source?.let(DartSourceEngine::ownerId) ?: task.sourceId
            return orgJsoup.call(owner, method, args, currentCoroutineContext())
        }
        if (method in NativeLegacyHttpHost.methods || method in NativeLegacyHttpContinuationHost.methods) {
            // The caller facade owns this live source; script arguments cannot choose another.
            val source = if (task.context[SourceTaskSourceSuppression]?.suppressed == true) null
                else task.context[SourceTaskSource]?.sourceForTask(task.sourceId)
            val nativeCallContext = currentCoroutineContext()
            val extensions = object : JsExtensions {
                override fun getSource() = source
                override fun getTag() = source?.getTag() ?: task.sourceId
                override fun getSourceNavigationContext() = nativeCallContext
            }
            task.context[SourceTaskHttpObserver]?.onCall?.invoke(method)
            return if (method in NativeLegacyHttpContinuationHost.methods)
                task.legacyHttpContinuations.call(method, args, extensions)
            else NativeLegacyHttpHost.call(method, args, extensions)
        }
        if (method in LegacyJavaHost.methods) {
            val source: BaseSource? = when (task.sourceKind) {
                "rss" -> appDb.rssSourceDao.getByKey(task.navigationSourceId)
                "tts" -> task.navigationSourceId.removePrefix("httpTts:").toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
                "book" -> appDb.bookSourceDao.getBookSource(task.navigationSourceId)
                else -> null
            }
            val extensions = object : JsExtensions {
                override fun getSource() = source
                override fun getTag() = source?.getTag() ?: task.sourceId
                override fun getSourceNavigationContext() = task.context
            }
            return LegacyJavaHost.call(extensions, method, args, task.sourceId)
        }
        val source: BaseSource = when (task.sourceKind) {
            "rss" -> appDb.rssSourceDao.getByKey(task.navigationSourceId)
            "tts" -> task.navigationSourceId.removePrefix("httpTts:").toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
            "book" -> appDb.bookSourceDao.getBookSource(task.navigationSourceId)
            else -> null
        } ?: error("Browser source not found")
        if (method == "browser.open") {
            require(args.size in 2..3 && args[0] is String && args[1] is String)
            val options = args.getOrNull(2) as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val url = args[0] as String
            val response =
                SourceVerificationHelp.getVerificationResult(
                    source,
                    url,
                    args[1] as String,
                    true,
                    options["refetchAfterSuccess"] == true,
                    html = options["html"] as? String,
                    coroutineContext = task.context,
                )
            return when (response) {
                is VerificationResult.Response ->
                    mapOf(
                        "url" to response.value.first,
                        "body" to response.value.second,
                        "cookie" to CookieStore.getCookie(url),
                    )
                VerificationResult.Refetch ->
                    mapOf(
                        "url" to url,
                        "refetch" to true,
                        "cookie" to CookieStore.getCookie(url),
                    )
            }
        }
        task.context[SourceHostCallbacks]?.let {
            return it.call(method, args)
        }
        val ui =
            object : JsExtensions {
                override fun getSource() = source

                override fun getTag() = source.getTag()

                override fun getSourceNavigationContext() = task.context
            }
        when (method) {
            "browser.show" -> {
                require(
                    args.size in 1..4 &&
                        args[0] is String &&
                        args.drop(1).all { it == null || it is String }
                )
                ui.showBrowser(
                    args[0] as String,
                    args.getOrNull(1) as? String,
                    args.getOrNull(2) as? String,
                    args.getOrNull(3) as? String,
                )
            }
            "browser.start" -> {
                require(
                    args.size in 2..3 &&
                        args[0] is String &&
                        args[1] is String &&
                        (args.getOrNull(2) == null || args[2] is String)
                )
                SourceVerificationHelp.startBrowser(
                    source,
                    args[0] as String,
                    args[1] as String,
                    html = args.getOrNull(2) as? String,
                )
            }
            "browser.video" -> {
                require(args.size in 2..3 && args[0] is String && args[1] is String)
                source.openVideoPlayer(
                    args[0] as String,
                    args[1] as String,
                    args.getOrNull(2) == true,
                )
            }
            "browser.openUrl" -> {
                require(args.size in 1..2 && args[0] is String)
                ui.openUrl(args[0] as String, args.getOrNull(1) as? String)
            }
            else -> error("Unsupported app host API $method")
        }
        return null
    }

    override suspend fun migrate(sourceJson: String): SourceMigrationPreview {
        ensureStarted()
        return withContext(Dispatchers.Main.immediate) {
            check(!closed) { "Flutter source repository is closed" }
            val requestId = UUID.randomUUID().toString()
            val response = CompletableDeferred<Any?>()
            responses[requestId] = response
            try {
                channel!!.invokeMethod(
                    "migrate",
                    mapOf("protocolVersion" to 1, "sourceJson" to sourceJson),
                    object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            response.complete(result)
                        }

                        override fun error(code: String, message: String?, details: Any?) {
                            response.completeExceptionally(
                                protocolFailure(code, message, details)
                            )
                        }

                        override fun notImplemented() {
                            response.completeExceptionally(
                                IllegalStateException("Book-source migration protocol unavailable")
                            )
                        }
                    },
                )
                SourceMigrationPreview.fromChannel(withTimeout(30_000) { response.await() })
            } finally {
                responses.remove(requestId)
            }
        }
    }

    override suspend fun evaluate(
        sourceJson: String,
        script: String,
        bindings: Map<String, Any?>,
        ephemeral: Boolean,
    ): Any? {
        val sourceId = sourceIdentity(sourceJson)
        ensureStarted()
        return withContext(Dispatchers.Main.immediate) {
            check(!closed) { "Flutter source repository is closed" }
            val taskId = UUID.randomUUID().toString()
            val response = CompletableDeferred<Any?>()
            responses[taskId] = response
            hostTasks[taskId] = HostTask(sourceId, currentCoroutineContext())
            mutableTasks.value = mutableTasks.value + (taskId to SourceTaskState(taskId, "running"))
            try {
                channel!!.invokeMethod(
                    "evaluate",
                    mapOf(
                        "protocolVersion" to 1,
                        "taskId" to taskId,
                        "sourceJson" to sourceJson,
                        "script" to script,
                        "bindings" to bindings,
                        "ephemeral" to ephemeral,
                    ),
                    object : MethodChannel.Result {
                        override fun success(result: Any?) {
                            response.complete(result)
                        }

                        override fun error(code: String, message: String?, details: Any?) {
                            response.completeExceptionally(
                                protocolFailure(code, message, details)
                            )
                        }

                        override fun notImplemented() {
                            response.completeExceptionally(
                                IllegalStateException("Book-source evaluation protocol unavailable")
                            )
                        }
                    },
                )
                val result = withTimeout(120_000) { response.await() }
                require(result is Map<*, *> && result.containsKey("value")) {
                    "Invalid script evaluation result"
                }
                result["value"]
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try {
                        channel?.invokeMethod("cancel", mapOf("taskId" to taskId))
                    } finally {
                        responses.remove(taskId)
                        hostTasks.remove(taskId)?.closeLegacyRules()
                        browserJobs.remove(taskId)?.forEach { it.cancel() }
                        mutableTasks.value = mutableTasks.value - taskId
                    }
                }
            }
        }
    }

    private suspend fun auxiliaryCall(
        method: String,
        arguments: Map<String, Any?>,
        sourceId: String? = null,
        sourceJson: Map<String, Any?>? = null,
        timeoutMs: Long = 10_000,
    ): Any? {
        ensureStarted()
        return withContext(Dispatchers.Main.immediate) {
            check(!closed) { "Flutter source repository is closed" }
            val taskId = UUID.randomUUID().toString()
            val effectiveId = sourceId ?: "auxiliary:$taskId"
            val response = CompletableDeferred<Any?>()
            responses[taskId] = response
            hostTasks[taskId] = HostTask(
                effectiveId, currentCoroutineContext(),
                sourceJson?.get("sourceKind") as? String ?: "auxiliary",
                sourceJson?.get("navigationSourceId") as? String ?: effectiveId,
            )
            mutableTasks.value += taskId to SourceTaskState(taskId, "running")
            try {
                channel!!.invokeMethod(
                    method,
                    arguments + mapOf("protocolVersion" to 1, "taskId" to taskId),
                    object : MethodChannel.Result {
                        override fun success(result: Any?) { response.complete(result) }
                        override fun error(code: String, message: String?, details: Any?) {
                            response.completeExceptionally(protocolFailure(code, message, details))
                        }
                        override fun notImplemented() {
                            response.completeExceptionally(IllegalStateException("V8 $method protocol unavailable"))
                        }
                    },
                )
                withTimeout(timeoutMs + 5_000) { response.await() }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try { channel?.invokeMethod("cancel", mapOf("taskId" to taskId)) }
                    finally {
                        responses.remove(taskId)
                        hostTasks.remove(taskId)?.closeLegacyRules()
                        browserJobs.remove(taskId)?.forEach { it.cancel() }
                        mutableTasks.value -= taskId
                    }
                }
            }
        }
    }

    override suspend fun evaluateAuxiliary(
        script: String,
        bindings: Map<String, Any?>,
        sourceId: String?,
        sourceJson: Map<String, Any?>?,
        prelude: String?,
        timeoutMs: Long,
    ): Any? {
        val result = auxiliaryCall(
            "evaluateAuxiliary",
            mapOf("script" to script, "bindings" to bindings, "sourceId" to sourceId,
                  "sourceJson" to sourceJson, "prelude" to prelude, "timeoutMs" to timeoutMs),
            sourceId, sourceJson, timeoutMs,
        )
        require(result is Map<*, *> && result.containsKey("value")) { "Invalid V8 auxiliary result" }
        return result["value"]
    }

    override suspend fun checkAuxiliarySyntax(script: String): V8ScriptDiagnostic? {
        val result = auxiliaryCall("checkAuxiliarySyntax", mapOf("script" to script)) ?: return null
        require(result is Map<*, *>) { "Invalid V8 syntax result" }
        val message = result["message"] as? String ?: error("Syntax message missing")
        val line = (result["lineNumber"] as? Number)?.toInt() ?: error("Syntax line missing")
        val column = (result["columnNumber"] as? Number)?.toInt() ?: error("Syntax column missing")
        require(line > 0 && column > 0) { "Syntax positions must be one-based" }
        return V8ScriptDiagnostic(message, line, column)
    }

    override suspend fun clearSourceState(sourceId: String) {
        auxiliaryCall("clearSourceState", mapOf("sourceId" to sourceId), sourceId = sourceId)
    }

    override suspend fun execute(
        operation: String,
        sourceJson: String,
        input: Map<String, Any?>,
    ): List<Map<String, Any?>> {
        val sourceId = sourceIdentity(sourceJson)
        ensureStarted()
        val taskId = UUID.randomUUID().toString()
        return withContext(Dispatchers.Main.immediate) {
            check(!closed) { "Flutter source repository is closed" }
            mutableTasks.value = mutableTasks.value + (taskId to SourceTaskState(taskId, "running"))
            val response = CompletableDeferred<Any?>()
            responses[taskId] = response
            hostTasks[taskId] = HostTask(sourceId, currentCoroutineContext())
            try {
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
                                protocolFailure(code, message, details)
                            )
                        }

                        override fun notImplemented() {
                            response.completeExceptionally(
                                IllegalStateException("Dart engine protocol unavailable")
                            )
                        }
                    },
                )
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
                    try {
                        channel?.invokeMethod("cancel", mapOf("taskId" to taskId))
                    } finally {
                        responses.remove(taskId)
                        hostTasks.remove(taskId)?.closeLegacyRules()
                        browserJobs.remove(taskId)?.forEach { it.cancel() }
                        mutableTasks.value = mutableTasks.value - taskId
                    }
                }
            }
        }
    }

    override suspend fun close() =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (closed) return@withContext
            closed = true
            ready.completeExceptionally(
                IllegalStateException("Flutter source repository is closed")
            )
            // Callers own execute coroutines; cancelling our browser scope cannot wake them.
            val pending = responses.values.toList()
            responses.clear()
            hostTasks.values.forEach { it.closeLegacyRules() }
            hostTasks.clear()
            pending.forEach {
                it.completeExceptionally(
                    IllegalStateException("Flutter source repository is closed")
                )
            }
            browserJobs.values.flatten().forEach { it.cancel() }
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
                                protocolFailure(code, message, details)
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
