package io.legado.app.ui.book.toc.rule

import android.content.Context
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.legado.app.help.config.AppConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fragment-scoped presentation. Room and the Activity's durable session remain authoritative. */
internal class TxtTocManagerChannelBridge(
    private val context: Context,
    messenger: BinaryMessenger,
    private val model: TxtTocRuleManagementViewModel,
    private val back: () -> Unit,
    private val picker: Boolean,
    private val native: (String, Long?) -> Unit,
    private val active: () -> Boolean,
) : AutoCloseable {
    private val uiChannel = MethodChannel(messenger, "legado/embedded_ui")
    private val managerChannel = MethodChannel(messenger, "legado/txt_toc_manager")
    private val stateChannel = EventChannel(messenger, "legado/txt_toc_manager/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private val binding = UUID.randomUUID().toString()
    private var revision = 0L
    private var sequence = 0L
    private var rejectedField: String? = null
    private var observation: Job? = null
    private var sink: EventChannel.EventSink? = null
    private var closed = false

    private fun resumed() =
        active()

    private val labels = mapOf(
        "back" to io.legado.app.R.string.back, "search" to io.legado.app.R.string.search,
        "more" to io.legado.app.R.string.more_menu, "add" to io.legado.app.R.string.create,
        "importFile" to io.legado.app.R.string.import_local, "online" to io.legado.app.R.string.import_on_line,
        "importQr" to io.legado.app.R.string.import_by_qr_code, "default" to io.legado.app.R.string.import_default_rule,
        "help" to io.legado.app.R.string.help, "retry" to io.legado.app.R.string.retry,
        "edit" to io.legado.app.R.string.edit, "top" to io.legado.app.R.string.to_top,
        "bottom" to io.legado.app.R.string.to_bottom, "up" to io.legado.app.R.string.txt_toc_move_up, "down" to io.legado.app.R.string.txt_toc_move_down, "delete" to io.legado.app.R.string.delete,
        "all" to io.legado.app.R.string.select_all, "invert" to io.legado.app.R.string.revert_selection,
        "enableSelection" to io.legado.app.R.string.enable_selection, "disableSelection" to io.legado.app.R.string.disable_selection,
        "export" to io.legado.app.R.string.export_selection, "share" to io.legado.app.R.string.share_selected_source,
        "confirm" to io.legado.app.R.string.ok, "cancel" to io.legado.app.R.string.cancel,
        "deleteTitle" to io.legado.app.R.string.draw, "sureDelete" to io.legado.app.R.string.sure_del,
        "sourceUrl" to io.legado.app.R.string.source_url, "path" to io.legado.app.R.string.path,
        "exportSuccess" to io.legado.app.R.string.export_success, "passphrase" to io.legado.app.R.string.shibboleth,
    ).mapValues { context.getString(it.value) }
    private fun snapshot(): Map<String, Any?> = txtTocManagerSnapshot(model.state.value, binding, ++revision, picker, labels)

    init {
        uiChannel.setMethodCallHandler { call, result ->
            scope.launch {
                reply(result) {
                    when (call.method) {
                        "bootstrap" -> mapOf(
                            "route" to if (picker) "/txt-toc-picker" else "/txt-toc-manager",
                            "locale" to if (context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                            "theme" to when (AppConfig.themeMode) {
                                "1", "3" -> "light"
                                "2" -> "dark"
                                else -> "system"
                            },
                        )
                        "navigation.close" -> { require(resumed()); back(); null }
                        else -> throw IllegalArgumentException("Unknown method")
                    }
                }
            }
        }
        managerChannel.setMethodCallHandler(::onMethodCall)
        stateChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) = listen(events)
            override fun onCancel(arguments: Any?) = cancelObservation()
        })
    }

    internal fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        scope.launch {
            commands.withLock {
                reply(result) {
                    when (call.method) {
                        "snapshot" -> snapshot()
                        "command" -> command(call.arguments)
                        else -> throw IllegalArgumentException("Unknown method")
                    }
                }
            }
        }
    }

    private suspend fun command(arguments: Any?): Map<String, Any?> {
        val values = arguments as? Map<*, *> ?: throw IllegalArgumentException("Invalid command")
        val op = values["op"] as? String ?: throw IllegalArgumentException("Missing command")
        require(values["binding"] == binding)
        val field = op.takeIf { it == "query" || it == "onlineInput" }
        try {
            val cancel = op in setOf("cancelGesture", "scroll") ||
                (op == "cancelQuery" && rejectedField in setOf(null, "query")) ||
                (op == "cancelModal" && rejectedField in setOf(null, "onlineInput"))
            require(rejectedField == null || rejectedField == field || cancel)
            require(resumed() || op == "cancelGesture")
            val next = (values["sequence"] as? Long) ?: (values["sequence"] as? Int)?.toLong() ?: error("Missing sequence")
            require(next > sequence); sequence = next
            val args = values.filterKeys { it != "binding" && it != "sequence" }
            var request: Pair<String, Long?>? = null
            txtTocManagerCommand(args, model, { op, id -> request = op to id }, back)
            if (model.privateStateReady) model.persist()
            request?.let { native(it.first, it.second) }
            if (field != null) {
                val state = model.state.value
                val text = if (field == "query") state.query else state.onlineInput
                val start = if (field == "query") state.queryStart else state.onlineStart
                val end = if (field == "query") state.queryEnd else state.onlineEnd
                require(text == args["text"] && start.toLong() == (args["start"] as Number).toLong() && end.toLong() == (args["end"] as Number).toLong())
            }
            require(!closed && (resumed() || op == "cancelGesture"))
            if (field != null || (op == "cancelModal" && model.state.value.let { it.deleteIds.isEmpty() && !it.online && it.exportUrl == null && it.passphrase == null }) || op == "cancelQuery") rejectedField = null
            return snapshot()
        } catch (error: Exception) {
            if (field != null && (rejectedField == null || rejectedField == field)) rejectedField = field
            throw error
        }
    }

    private suspend fun reply(result: MethodChannel.Result, block: suspend () -> Any?) {
        if (closed) { result.error("closed", "TXT TOC manager view is closed", null); return }
        try {
            result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            result.error("invalid_request", "Invalid TXT TOC manager request", null)
        } catch (_: Exception) {
            // Never return drafts, credentials, export paths or native exception messages.
            result.error("storage_error", "TXT TOC manager operation failed", null)
        }
    }

    internal fun listen(events: EventChannel.EventSink) {
        cancelObservation()
        if (closed) { events.error("closed", "TXT TOC manager view is closed", null); return }
        sink = events
        fun emit() {
            if (closed || sink !== events) return
            try {
                events.success(snapshot())
            } catch (_: IllegalArgumentException) {
                events.error("invalid_request", "Invalid TXT TOC manager state", null)
            } catch (_: Exception) {
                events.error("storage_error", "Could not read TXT TOC manager state", null)
            }
        }
        emit()
        observation = scope.launch { model.state.collect { emit() } }
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
        model.cancelGestures()
        uiChannel.setMethodCallHandler(null)
        managerChannel.setMethodCallHandler(null)
        stateChannel.setStreamHandler(null)
        scope.cancel()
    }
}
