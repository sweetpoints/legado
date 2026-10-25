package io.legado.app.ui.about

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal class DiagnosticsChannelBridge(context: Context, messenger: BinaryMessenger, private val host: DiagnosticsDialog) : AutoCloseable {
    private val path = if(host.crash) "crash_logs" else "app_logs"
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val methods = MethodChannel(messenger, "legado/$path")
    private val events = EventChannel(messenger, "legado/$path/state")
    private val owner = host.bind()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var closed = false
    init {
        ui.setMethodCallHandler { call, result -> reply(result) { when(call.method) {
            "bootstrap" -> mapOf("route" to if(host.crash) "/crash-logs" else "/app-logs",
                "locale" to if(context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                "theme" to when(AppConfig.themeMode) { "1", "3" -> "light"; "2" -> "dark"; else -> "system" })
            "navigation.close" -> { host.closePage(); null }
            else -> error("Unknown embedded operation")
        } } }
        methods.setMethodCallHandler { call, result -> reply(result) { when(call.method) {
            "snapshot" -> { require(call.arguments == null); host.snapshot(owner) }
            "command" -> host.command(owner, call.arguments)
            else -> error("Unknown diagnostics operation")
        } } }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                observation?.cancel()
                if(closed || arguments != null) { sink.error("invalid_request", "Diagnostics unavailable", null); return }
                observation = scope.launch {
                    val changes = if(host is AppLogDialog) host.model.state else (host as CrashLogsDialog).model.state
                    changes.collect {
                        try { sink.success(host.snapshot(owner)) }
                        catch (_: Exception) {
                            sink.error("retired", "Diagnostics view retired", null)
                            throw CancellationException("Diagnostics view retired")
                        }
                    }
                }
            }
            override fun onCancel(arguments: Any?) { observation?.cancel(); observation = null }
        })
    }
    private inline fun reply(result: MethodChannel.Result, action: () -> Any?) {
        if(closed) { result.error("closed", "Diagnostics closed", null); return }
        try { result.success(action()) } catch(_: Exception) { result.error("invalid_request", "Diagnostics operation rejected", null) }
    }
    override fun close() {
        if(closed) return; closed = true; observation?.cancel(); scope.cancel(); host.release(owner)
        ui.setMethodCallHandler(null); methods.setMethodCallHandler(null); events.setStreamHandler(null)
    }
}
