package io.legado.app.ui.book.source.manage

import android.content.Context
import androidx.annotation.MainThread
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import kotlinx.coroutines.*

/** A Fragment-scoped presentation bridge; the Activity owns the durable manager ViewModel. */
internal class SourceManagerChannelBridge(
    private val context: Context,
    messenger: BinaryMessenger,
    private val model: BookSourceManagerViewModel,
) : AutoCloseable {
    private val uiChannel = MethodChannel(messenger, "legado/embedded_ui")
    private val managerChannel = MethodChannel(messenger, "legado/source_manager")
    private val stateChannel = EventChannel(messenger, "legado/source_manager/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var sink: EventChannel.EventSink? = null
    private var closed = false

    init {
        uiChannel.setMethodCallHandler { call, result ->
            reply(result) {
                when (call.method) {
                    "bootstrap" -> mapOf(
                        "route" to "/source-manager",
                        "locale" to if (context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                        "theme" to when (AppConfig.themeMode) {
                            "1", "3" -> "light"
                            "2" -> "dark"
                            else -> "system"
                        },
                    )
                    "navigation.close" -> { model.effect("back"); null }
                    else -> invalidManagerCommand("Unknown embedded UI method")
                }
            }
        }
        managerChannel.setMethodCallHandler(::onMethodCall)
        stateChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) = listen(events)
            override fun onCancel(arguments: Any?) = cancelObservation()
        })
    }

    internal fun onMethodCall(call: MethodCall, result: MethodChannel.Result) = reply(result) {
        when (call.method) {
            "snapshot" -> sourceManagerSnapshotJson(model.state.value)
            "command" -> { parseSourceManagerCommand(call.arguments).apply(model); null }
            else -> invalidManagerCommand("Unknown source manager method")
        }
    }

    private inline fun reply(result: MethodChannel.Result, block: () -> Any?) {
        if (closed) { result.error("closed", "Source manager view is closed", null); return }
        try {
            result.success(block())
        } catch (error: SourceManagerInvalidCommand) {
            result.error("invalid_request", error.message, null)
        } catch (_: Exception) {
            // Source drafts, credentials, exported files and native exceptions stay on the device.
            result.error("storage_error", "Source manager operation failed", null)
        }
    }

    internal fun listen(events: EventChannel.EventSink) {
        cancelObservation()
        if (closed) { events.error("closed", "Source manager view is closed", null); return }
        sink = events
        fun emit(value: SourceManagerState) {
            if (closed || sink !== events) return
            try {
                events.success(sourceManagerSnapshotJson(value))
            } catch (_: SourceManagerInvalidCommand) {
                events.error("invalid_request", "Source manager state exceeds 16 MiB", null)
            } catch (_: Exception) {
                events.error("storage_error", "Could not read source manager state", null)
            }
        }
        emit(model.state.value)
        observation = scope.launch { model.state.collect { emit(it) } }
    }

    internal fun cancelObservation() {
        observation?.cancel()
        observation = null
        sink = null
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        cancelObservation()
        // Dart disposal may not deliver its last command before this engine detaches. Drag is
        // presentation-only state: cancel it here without committing a repository move.
        model.finishDrag(cancelled = true)
        uiChannel.setMethodCallHandler(null)
        managerChannel.setMethodCallHandler(null)
        stateChannel.setStreamHandler(null)
        scope.cancel()
    }
}

internal class SourceManagerInvalidCommand(message: String) : IllegalArgumentException(message)
private fun invalidManagerCommand(message: String): Nothing = throw SourceManagerInvalidCommand(message)

/** Explicit stable field names survive R8; never serialize entities, effects or File objects. */
internal fun sourceManagerSnapshotJson(state: SourceManagerState): String {
    val json = managerStateGson.toJson(linkedMapOf(
        "schemaVersion" to 1,
        "query" to state.query,
        "rows" to state.rows.map { row -> linkedMapOf(
            "url" to row.url, "name" to row.name, "group" to row.group, "order" to row.order,
            "enabled" to row.enabled, "exploreEnabled" to row.exploreEnabled,
            "hasExplore" to row.hasExplore, "hasLogin" to row.hasLogin, "hasJs" to row.hasJs,
            "updated" to row.updated, "response" to row.response, "weight" to row.weight,
            "checkStatus" to row.checkStatus, "checkDetail" to row.checkDetail, "host" to row.host,
        ) },
        "groups" to state.groups, "selected" to state.selected.toList(), "counts" to state.counts,
        "sort" to state.sort.name, "ascending" to state.ascending, "byDomain" to state.byDomain,
        "showStatus" to state.showStatus, "status" to state.status, "busy" to state.busy,
        "loading" to state.loading, "error" to state.error, "checkMessage" to state.checkMessage,
        "debugMessages" to state.debugMessages, "dialog" to state.dialog?.name, "draft" to state.draft,
        "blockNavigation" to state.blockNavigation, "draggingKey" to state.draggingKey,
        "importHistory" to state.importHistory, "canMove" to state.canMove,
        "feedback" to state.feedback?.let { linkedMapOf(
            "url" to it.url, "summary" to it.summary, "canSharePassphrase" to it.canSharePassphrase,
            "passphrase" to it.passphrase,
        ) },
    ))
    if (json.length >= MAX_MANAGER_STATE_BYTES || json.toByteArray(Charsets.UTF_8).size >= MAX_MANAGER_STATE_BYTES) {
        invalidManagerCommand("Source manager state exceeds 16 MiB")
    }
    return json
}

private val managerStateGson = GSON.newBuilder().serializeNulls().create()

private const val MAX_MANAGER_STATE_BYTES = 16 * 1024 * 1024
private const val MAX_MANAGER_TEXT_BYTES = 8 * 1024 * 1024

internal val sourceManagerAllowedActions = setOf(
    "delete", "online", "check", "export", "share", "interval", "descending", "domain",
    "show-status", "block-navigation", "retry", "up", "down", "top", "bottom", "explore",
    "filter-enabled", "filter-disabled", "filter-login", "filter-no-group", "filter-explore",
    "filter-no-explore", "back", "add", "add-js", "qr", "local", "import", "groups",
    "check-config", "help", "edit", "debug", "login", "search", "cancel-check", "copy",
)

internal data class SourceManagerCommand(
    val op: String,
    val value: String = "",
    val key: String? = null,
    val mutation: SourceMutation? = null,
    val sort: BookSourceSort? = null,
    val index: Int = 0,
    val cancelled: Boolean = false,
    val keys: Set<String> = emptySet(),
    val selectedAtStart: Set<String> = emptySet(),
) {
    fun apply(model: BookSourceManagerViewModel) {
        when (op) {
            "query" -> model.query(value)
            "toggle" -> model.toggle(requireNotNull(key))
            "selectAll" -> model.selectAll()
            "invert" -> model.invert()
            "action" -> handleSourceManagerAction(model, value, key.orEmpty())
            "mutation" -> handleSourceManagerMutation(model, requireNotNull(mutation), key)
            "sort" -> model.sort(requireNotNull(sort))
            "status" -> model.status(value)
            "draft" -> model.draft(value)
            "passphrase" -> model.showPassphrase()
            "forgetImport" -> model.forgetImport(value)
            "confirm" -> model.confirm()
            "dismiss" -> model.dismiss()
            "beginDrag" -> model.beginDrag(requireNotNull(key))
            "previewDrag" -> model.previewDrag(index)
            "finishDrag" -> model.finishDrag(cancelled)
            "slide" -> model.slide(keys, selectedAtStart)
        }
    }
}

/** Parse completely before touching the ViewModel. A command is applied once, synchronously on Main. */
internal fun parseSourceManagerCommand(arguments: Any?): SourceManagerCommand {
    val args = arguments as? Map<*, *> ?: invalidManagerCommand("Command must be a map")
    fun text(name: String, optional: Boolean = false): String {
        val value = args[name]
        if (value == null && optional) return ""
        val result = value as? String ?: invalidManagerCommand("Invalid command field: $name")
        if (result.length > MAX_MANAGER_TEXT_BYTES || result.toByteArray(Charsets.UTF_8).size > MAX_MANAGER_TEXT_BYTES) {
            invalidManagerCommand("Command text exceeds 8 MiB")
        }
        return result
    }
    fun key(optional: Boolean = false): String? =
        if (optional && args["key"] == null) null else text("key").also {
            if (it.isBlank()) invalidManagerCommand("Source key must not be blank")
        }
    fun keys(name: String): Set<String> {
        val values = args[name] as? List<*> ?: invalidManagerCommand("Invalid key list: $name")
        if (values.size > 10000) invalidManagerCommand("Too many source keys")
        var bytes = 0L
        val result = values.map {
            val value = (it as? String)?.takeIf { item -> item.isNotBlank() && item.length <= 65536 }
                ?: invalidManagerCommand("Invalid source key")
            bytes += value.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_MANAGER_TEXT_BYTES) invalidManagerCommand("Source keys exceed 8 MiB")
            value
        }
        if (result.distinct().size != result.size) invalidManagerCommand("Duplicate source keys")
        return result.toSet()
    }
    val op = text("op")
    val allowedFields = when (op) {
        "query", "status", "draft", "forgetImport", "sort" -> setOf("op", "value")
        "toggle", "beginDrag" -> setOf("op", "key")
        "action" -> setOf("op", "action", "key")
        "mutation" -> setOf("op", "mutation", "key")
        "previewDrag" -> setOf("op", "index")
        "finishDrag" -> setOf("op", "cancelled")
        "slide" -> setOf("op", "keys", "selectedAtStart")
        "selectAll", "invert", "passphrase", "confirm", "dismiss" -> setOf("op")
        else -> invalidManagerCommand("Unknown source manager operation")
    }
    if (args.keys.any { it !in allowedFields }) invalidManagerCommand("Unexpected command field")
    return when (op) {
        "query", "draft", "forgetImport" -> SourceManagerCommand(op, value = text("value"))
        "status" -> SourceManagerCommand(op, value = text("value").also {
            if (it !in setOf("", "NEEDS_CHECK", "PASSED", "FAILED")) invalidManagerCommand("Unknown check status")
        })
        "toggle", "beginDrag" -> SourceManagerCommand(op, key = key())
        "action" -> SourceManagerCommand(op, value = text("action").also {
            if (it !in sourceManagerAllowedActions) invalidManagerCommand("Unknown source manager action")
        }, key = text("key", optional = true))
        "mutation" -> SourceManagerCommand(op, key = key(optional = true), mutation =
            SourceMutation.entries.firstOrNull { it.name == text("mutation") }
                ?: invalidManagerCommand("Unknown source mutation"))
        "sort" -> SourceManagerCommand(op, sort = BookSourceSort.entries.firstOrNull { it.name == text("value") }
            ?: invalidManagerCommand("Unknown source sort"))
        "previewDrag" -> SourceManagerCommand(op, index = (args["index"] as? Int)?.takeIf { it >= 0 }
            ?: invalidManagerCommand("Drag index must be a nonnegative Int"))
        "finishDrag" -> SourceManagerCommand(op, cancelled = args["cancelled"] as? Boolean
            ?: invalidManagerCommand("cancelled must be a Boolean"))
        "slide" -> SourceManagerCommand(op, keys = keys("keys"), selectedAtStart = keys("selectedAtStart"))
        else -> SourceManagerCommand(op)
    }
}
