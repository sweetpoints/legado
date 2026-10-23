package io.legado.app.ui.dict.rule

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
internal class DictionaryManagerChannelBridge(
    private val context: Context,
    messenger: BinaryMessenger,
    private val model: DictionaryRuleManagementViewModel,
    private val back: () -> Unit,
) : AutoCloseable {
    private val uiChannel = MethodChannel(messenger, "legado/embedded_ui")
    private val managerChannel = MethodChannel(messenger, "legado/dictionary_manager")
    private val stateChannel = EventChannel(messenger, "legado/dictionary_manager/state")
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
        (context as LifecycleOwner).lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun snapshot(): Map<String, Any?> = dictionaryManagerSnapshot(model.state.value, binding, ++revision)

    init {
        uiChannel.setMethodCallHandler { call, result ->
            scope.launch {
                reply(result) {
                    when (call.method) {
                        "bootstrap" -> mapOf(
                            "route" to "/dictionary-manager",
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
        val field = op == "input"
        try {
            require(rejectedField == null || rejectedField == op || op in setOf("back", "online", "cancelSlide", "cancelReorder"))
            require(resumed())
            val next = (values["sequence"] as? Long)
                ?: (values["sequence"] as? Int)?.toLong()
                ?: throw IllegalArgumentException("Missing sequence")
            require(next > sequence)
            sequence = next
            val args = values.filterKeys { it != "binding" && it != "sequence" }
            dictionaryManagerCommand(args, model) { action, name ->
                (context as DictRuleActivity).nativeAction(action, name)
            }
            if (field) require(model.state.value.onlineInput == args["value"])
            require(!closed && resumed())
            if (field || (op == "online" && args["value"] == false) || (op == "back" && !model.state.value.online)) rejectedField = null
            return snapshot()
        } catch (error: Exception) {
            if (field && (rejectedField == null || rejectedField == op)) rejectedField = op
            throw error
        }
    }

    private suspend fun reply(result: MethodChannel.Result, block: suspend () -> Any?) {
        if (closed) { result.error("closed", "Dictionary manager view is closed", null); return }
        try {
            result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            result.error("invalid_request", "Invalid Dictionary manager request", null)
        } catch (_: Exception) {
            // Never return drafts, credentials, export paths or native exception messages.
            result.error("storage_error", "Dictionary manager operation failed", null)
        }
    }

    internal fun listen(events: EventChannel.EventSink) {
        cancelObservation()
        if (closed) { events.error("closed", "Dictionary manager view is closed", null); return }
        sink = events
        fun emit() {
            if (closed || sink !== events) return
            try {
                events.success(snapshot())
            } catch (_: IllegalArgumentException) {
                events.error("invalid_request", "Dictionary manager state exceeds limit", null)
            } catch (_: Exception) {
                events.error("storage_error", "Could not read Dictionary manager state", null)
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
