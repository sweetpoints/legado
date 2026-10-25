package io.legado.app.ui.book.bookmark

import android.content.Context
import io.flutter.plugin.common.*
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal class BookmarkEditorChannelBridge(
    context: Context,
    messenger: BinaryMessenger,
    private val coordinator: BookmarkEditorNativeCoordinator,
) : AutoCloseable {
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val methods = MethodChannel(messenger, "legado/bookmark_editor")
    private val events = EventChannel(messenger, "legado/bookmark_editor/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = coordinator.bind()
    private var observation: Job? = null
    private var closed = false

    init {
        ui.setMethodCallHandler { call, result ->
            reply(result) {
                when (call.method) {
                    "bootstrap" -> {
                        coordinator.snapshot(owner)
                        mapOf(
                            "route" to "/bookmark-editor",
                            "locale" to
                                if (context.resources.configuration.locales[0].language == "zh")
                                    "zh"
                                else "en",
                            "theme" to
                                when (AppConfig.themeMode) {
                                    "1",
                                    "3" -> "light"
                                    "2" -> "dark"
                                    else -> "system"
                                },
                        )
                    }
                    "navigation.close" -> {
                        coordinator.command(
                            owner,
                            mapOf("op" to "cancel", "generation" to coordinator.generation),
                        )
                        null
                    }
                    else -> error("Unknown embedded method")
                }
            }
        }
        methods.setMethodCallHandler { call, result ->
            reply(result) {
                when (call.method) {
                    "snapshot" -> {
                        require(call.arguments == null)
                        coordinator.snapshot(owner)
                    }
                    "command" -> coordinator.command(owner, call.arguments)
                    else -> error("Unknown Bookmark editor method")
                }
            }
        }
        events.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                    observation?.cancel()
                    if (closed || arguments != null) {
                        sink.error("invalid_request", "Bookmark editor subscription unavailable", null)
                        return
                    }
                    observation = scope.launch {
                        coordinator.changes.collect {
                            try {
                                sink.success(coordinator.snapshot(owner))
                            } catch (_: Exception) {
                                sink.error("invalid_state", "Bookmark editor view unavailable", null)
                                // Main.immediate may emit before observation receives this Job.
                                // End this collector itself rather than canceling a previous one.
                                throw CancellationException("Bookmark editor binding retired")
                            }
                        }
                    }
                }

                override fun onCancel(arguments: Any?) {
                    observation?.cancel()
                    observation = null
                }
            }
        )
    }

    private inline fun reply(result: MethodChannel.Result, action: () -> Any?) {
        if (closed) {
            result.error("closed", "Bookmark editor view closed", null)
            return
        }
        try {
            result.success(action())
        } catch (_: Exception) {
            result.error("invalid_request", "Bookmark editor operation unavailable or invalid", null)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        coordinator.detach(owner)
        observation?.cancel()
        scope.cancel()
        ui.setMethodCallHandler(null)
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
    }
}
