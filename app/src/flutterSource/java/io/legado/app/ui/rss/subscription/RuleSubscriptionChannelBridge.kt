package io.legado.app.ui.rss.subscription

import android.content.Context
import androidx.lifecycle.Lifecycle
import io.flutter.plugin.common.*
import io.legado.app.help.config.AppConfig
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI projection only: the existing VM and journal remain the sole write authority. */
internal class RuleSubscriptionChannelBridge(
    context: Context,
    messenger: BinaryMessenger,
    private val model: RuleSubscriptionViewModel,
) : AutoCloseable {
    private val contextHost = context as RuleSubActivity
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val methods = MethodChannel(messenger, "legado/rule_subscriptions")
    private val events = EventChannel(messenger, "legado/rule_subscriptions/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private val generation = UUID.randomUUID().toString()
    private var revision = 0L
    private var sequence = 0L
    private var closed = false
    private var observation: Job? = null

    init {
        ui.setMethodCallHandler { call, result -> reply(result) {
            when (call.method) {
                "bootstrap" -> mapOf("route" to "/rule-subscriptions",
                    "locale" to if (context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                    "theme" to when (AppConfig.themeMode) { "1", "3" -> "light"; "2" -> "dark"; else -> "system" })
                "navigation.close" -> { check(contextHost.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)); back(); null }
                else -> error("Unknown embedded method")
            }
        } }
        methods.setMethodCallHandler { call, result -> reply(result) {
            when (call.method) {
                "snapshot" -> snapshot()
                "command" -> command(call.arguments)
                else -> error("Unknown subscription method")
            }
        } }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                observation?.cancel()
                observation = scope.launch { model.state.collect { if (!closed) sink.success(snapshot()) } }
            }
            override fun onCancel(arguments: Any?) { observation?.cancel(); observation = null }
        })
    }
    private fun back() {
        if (model.state.value.editor != null) model.cancelEditor()
        else if (!model.state.value.busy) model.close()
    }
    private suspend fun command(arguments: Any?): Map<String, Any?> = gate.withLock {
        check(!closed)
        check((contextHost.lifecycle.currentState).isAtLeast(Lifecycle.State.RESUMED))
        val args = arguments as? Map<*, *> ?: error("Invalid command")
        require(args["generation"] == generation)
        require((args["revision"] as? Number)?.toLong() == revision)
        val op = args["op"] as? String ?: error("Missing operation")
        val state = model.state.value
        check(!state.closed && !state.busy)
        check(op == "back" || (!state.loading && (state.loaded || op == "retry")))
        fun text() = args["value"] as? String ?: error("Missing value")
        fun id(key: String = "id") = (args[key] as? String)?.toLongOrNull() ?: error("Invalid id")
        if (op in setOf("name", "url", "interval", "type", "automatic", "silent")) {
            check(state.editor != null && !state.pendingSave)
        }
        when (op) {
            "create" -> model.create()
            "edit" -> model.edit(id())
            "delete" -> model.delete(id())
            "open" -> model.open(id())
            "name" -> model.name(text())
            "url" -> model.url(text())
            "interval" -> { val value = text(); require(value.all(Char::isDigit)); model.interval(value) }
            "type" -> { val type = (args["value"] as? Number)?.toInt() ?: -1; require(type in 0..2); model.type(type) }
            "automatic" -> model.automatic(args["value"] as? Boolean ?: error("Invalid flag"))
            "silent" -> model.silent(args["value"] as? Boolean ?: error("Invalid flag"))
            "save" -> model.save()
            "cancel" -> model.cancelEditor()
            "retry" -> model.retry()
            "back" -> back()
            "move" -> { check(model.beginDrag()); model.move(id(), id("target")); model.finishDrag() }
            else -> error("Invalid operation")
        }
        // VM operations launch Room/file work. Acknowledgement waits for that work,
        // so a queued field cannot be silently dropped behind a busy save/edit.
        model.state.first { !it.busy }
        if (op in setOf("name", "url", "interval", "type", "automatic", "silent")) model.flush()
        revision++
        snapshot()
    }
    private fun snapshot(): Map<String, Any?> {
        val state = model.state.value
        val editor = state.editor
        return mapOf("generation" to generation, "revision" to revision, "sequence" to ++sequence,
            "loaded" to state.loaded, "loading" to state.loading, "busy" to state.busy,
            "pendingSave" to state.pendingSave, "closed" to state.closed,
            "issue" to state.issue?.name,
            "rows" to state.rows.map { mapOf("id" to it.id.toString(), "name" to it.name, "url" to it.url, "type" to it.type) },
            "editor" to editor?.let { mapOf("id" to it.newId.toString(), "name" to it.name,
                "url" to it.url, "type" to it.type, "automatic" to it.automatic,
                "interval" to it.interval, "silent" to it.silent, "silentEnabled" to it.silentEnabled) })
    }
    private fun reply(result: MethodChannel.Result, action: suspend () -> Any?) {
        if (closed) { result.error("closed", "Subscription view closed", null); return }
        scope.launch {
            try { result.success(action()) }
            catch (cancel: CancellationException) { result.error("closed", "Subscription view retired", null); throw cancel }
            catch (_: Exception) { result.error("invalid_request", "Subscription operation rejected", null) }
        }
    }
    override fun close() {
        closed = true
        model.cancelDrag()
        observation?.cancel()
        scope.cancel()
        ui.setMethodCallHandler(null); methods.setMethodCallHandler(null); events.setStreamHandler(null)
    }
}
