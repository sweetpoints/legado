package io.legado.app.ui.rss.favorites

import android.content.Context
import io.flutter.plugin.common.*
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal class RssFavoriteConfigChannelBridge(
    context: Context,
    messenger: BinaryMessenger,
    private val coordinator: RssFavoriteConfigNativeCoordinator,
) : AutoCloseable {
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val methods = MethodChannel(messenger, "legado/rss_favorite_config")
    private val events = EventChannel(messenger, "legado/rss_favorite_config/state")
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
                            "route" to "/rss-favorite-config",
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
                    else -> error("Unknown favorites method")
                }
            }
        }
        events.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                    observation?.cancel()
                    if (closed || arguments != null) {
                        sink.error("invalid_request", "Favorites subscription unavailable", null)
                        return
                    }
                    observation = scope.launch {
                        coordinator.changes.collect {
                            try {
                                sink.success(coordinator.snapshot(owner))
                            } catch (_: Exception) {
                                sink.error("invalid_state", "Favorites view unavailable", null)
                                // Main.immediate may emit before observation receives this Job.
                                // End this collector itself rather than canceling a previous one.
                                throw CancellationException("Favorites binding retired")
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
            result.error("closed", "Favorites view closed", null)
            return
        }
        try {
            result.success(action())
        } catch (_: Exception) {
            result.error("invalid_request", "Favorites operation unavailable or invalid", null)
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
