package io.legado.app.ui.code

import android.content.Context
import io.flutter.plugin.common.*
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.source.edit.requireSourceEditorBudget
import io.legado.app.ui.book.source.edit.sourceEditorSafeError
import kotlinx.coroutines.*

internal class CodeEditorChannelBridge(
    context: Context,
    messenger: BinaryMessenger,
    private val coordinator: CodeEditorNativeCoordinator,
) : AutoCloseable {
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val methods = MethodChannel(messenger, "legado/code_editor")
    private val events = EventChannel(messenger, "legado/code_editor/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var closed = false
    init {
        ui.setMethodCallHandler { call, result -> reply(result) {
            when (call.method) {
                "bootstrap" -> mapOf("route" to "/code-editor",
                    "locale" to if (context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                    "theme" to when (AppConfig.themeMode) { "1", "3" -> "light"; "2" -> "dark"; else -> "system" })
                "navigation.close" -> { coordinator.exit(); null }
                else -> error("Unknown embedded UI method")
            }
        } }
        methods.setMethodCallHandler { call, result -> reply(result) {
            when (call.method) {
                "snapshot" -> codeEditorSnapshot(coordinator.state.value)
                "command" -> { parseCodeEditorCommand(call.arguments).apply(coordinator); null }
                else -> error("Unknown code editor method")
            }
        } }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                observation?.cancel()
                if (closed) { sink.error("closed", "Code editor view is closed", null); return }
                observation = scope.launch { coordinator.state.collect { value ->
                    try { sink.success(codeEditorSnapshot(value)) }
                    catch (_: Exception) { sink.error("invalid_state", "Could not read code editor state", null) }
                } }
            }
            override fun onCancel(arguments: Any?) { observation?.cancel(); observation = null }
        })
    }
    private inline fun reply(result: MethodChannel.Result, block: () -> Any?) {
        if (closed) { result.error("closed", "Code editor view is closed", null); return }
        try { result.success(block()) }
        catch (_: Exception) { result.error("invalid_request", "Code editor operation is unavailable or invalid", null) }
    }
    override fun close() {
        if (closed) return
        closed = true
        observation?.cancel(); scope.cancel()
        ui.setMethodCallHandler(null); methods.setMethodCallHandler(null); events.setStreamHandler(null)
    }
}

internal fun codeEditorSnapshot(value: CodeEditorPresentation): Map<String, Any?> {
    val state = value.state
    val session = state.session
    val search = session?.search ?: CodeEditorSearch()
    return linkedMapOf<String, Any?>(
        "schemaVersion" to 1, "busy" to state.busy, "error" to codeEditorSafeError(state.error),
        "confirmDiscard" to state.confirmDiscard, "hasSession" to (session != null),
        "title" to session?.title, "writable" to (session?.writable == true), "safe" to value.safe,
        "finished" to (session?.finished == true), "showDebugSource" to (session?.showDebugSource == true),
        "showLoginSource" to (session?.showLoginSource == true),
        "checkJavaScriptSyntax" to (session?.checkJavaScriptSyntax == true),
        "generation" to if (session == null) 0 else value.generation, "keyboardRows" to state.keyboardRows.coerceIn(1, 5),
        "autoWrap" to value.autoWrap,
        "assists" to state.assists.map { mapOf("key" to it.key, "value" to it.value) },
        "search" to mapOf("query" to search.query, "replacement" to search.replacement,
            "visible" to search.visible, "replaceVisible" to search.replaceVisible,
            "regex" to search.regex, "ignoreCase" to search.ignoreCase,
            "queryStart" to search.querySelection.start, "queryEnd" to search.querySelection.end,
            "replacementStart" to search.replacementSelection.start, "replacementEnd" to search.replacementSelection.end),
        "engine" to value.status.let { mapOf("ready" to it.ready, "reading" to it.reading,
            "failed" to it.failed, "replacing" to it.replacing,
            "replacementError" to codeEditorSafeError(it.replacementError), "searchResult" to it.searchResult) },
    ).also { requireSourceEditorBudget(it) }
}

private val codeEditorPrivateFile = Regex(
    """(?<![A-Za-z0-9:/])(?:/[^\s"'<>():,;]*/)?(?:code-editor-sessions/[0-9a-fA-F-]{36}\.json(?:\.bak|\.new)?|code-text-[0-9a-fA-F-]{36}-[0-9a-fA-F-]{36}\.txt)""",
)
internal fun codeEditorSafeError(message: String?): String? = sourceEditorSafeError(message)
    ?.replace(codeEditorPrivateFile, "私有编辑数据读写失败")

internal data class CodeEditorCommand(
    val op: String, val action: CodeEditorAction? = null,
    val search: CodeEditorSearch? = null, val text: String? = null,
) {
    fun apply(coordinator: CodeEditorNativeCoordinator) {
        when (op) {
            "action" -> coordinator.action(checkNotNull(action))
            "search" -> coordinator.search(checkNotNull(search))
            "insert" -> coordinator.insert(checkNotNull(text))
            "exit" -> coordinator.exit()
            "retry" -> coordinator.retry()
            "restart" -> coordinator.restart()
            "keepEditing" -> coordinator.keepEditing()
            "discard" -> coordinator.discard()
        }
    }
}

internal fun parseCodeEditorCommand(arguments: Any?): CodeEditorCommand {
    require(arguments is Map<*, *>)
    val op = arguments["op"] as? String ?: error("Missing operation")
    val keys = when (op) {
        "action" -> setOf("op", "name")
        "search" -> setOf("op", "value")
        "insert" -> setOf("op", "text")
        "exit", "retry", "restart", "keepEditing", "discard" -> setOf("op")
        else -> error("Unknown operation")
    }
    require(arguments.keys == keys)
    return when (op) {
        "action" -> CodeEditorCommand(op, action = CodeEditorAction.entries.firstOrNull { it.name == arguments["name"] }
            ?: error("Unknown action"))
        "insert" -> {
            val text = arguments["text"] as? String ?: error("Invalid insert")
            require(text.length <= 8 * 1024 * 1024 && text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
            CodeEditorCommand(op, text = text)
        }
        "search" -> {
            val value = arguments["value"] as? Map<*, *> ?: error("Invalid search")
            val required = setOf("query", "replacement", "visible", "replaceVisible", "regex", "ignoreCase",
                "queryStart", "queryEnd", "replacementStart", "replacementEnd")
            require(value.keys == required)
            fun text(key: String) = (value[key] as? String)?.also { require(it.length <= 65536) } ?: error("Invalid search text")
            fun flag(key: String) = value[key] as? Boolean ?: error("Invalid search flag")
            fun selection(prefix: String, text: String): CodeEditorSelection {
                val start = value["${prefix}Start"] as? Int ?: error("Invalid selection")
                val end = value["${prefix}End"] as? Int ?: error("Invalid selection")
                require(start in 0..text.length && end in 0..text.length)
                return CodeEditorSelection(start, end)
            }
            val query = text("query"); val replacement = text("replacement")
            CodeEditorCommand(op, search = CodeEditorSearch(flag("visible"), flag("replaceVisible"), query,
                replacement, flag("regex"), flag("ignoreCase"),
                selection("query", query), selection("replacement", replacement)))
        }
        else -> CodeEditorCommand(op)
    }
}

internal class CodeEditorSurfaceFactory(private val coordinator: CodeEditorNativeCoordinator) :
    PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        require(args is Map<*, *> && args.keys == setOf("generation"))
        val generation = args["generation"] as? Int ?: error("Invalid editor generation")
        val surface = coordinator.createSurface(context, generation)
        return object : PlatformView {
            override fun getView() = surface.view
            override fun dispose() = surface.close()
        }
    }
}
