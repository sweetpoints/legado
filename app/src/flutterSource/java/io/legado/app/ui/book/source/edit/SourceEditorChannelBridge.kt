package io.legado.app.ui.book.source.edit

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.*

/** View-owned transport only. Draft persistence and native delivery stay in the existing model. */
internal class SourceEditorChannelBridge(
    private val context: Context,
    messenger: BinaryMessenger,
    private val model: BookSourceComposeViewModel,
    private val onPaste: () -> Unit,
) : AutoCloseable {
    private val ui = MethodChannel(messenger, "legado/embedded_ui")
    private val editor = MethodChannel(messenger, "legado/source_editor")
    private val events = EventChannel(messenger, "legado/source_editor/state")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var closed = false

    init {
        ui.setMethodCallHandler { call, result -> reply(result) {
            when (call.method) {
                "bootstrap" -> mapOf("route" to "/source-editor",
                    "locale" to if (context.resources.configuration.locales[0].language == "zh") "zh" else "en",
                    "theme" to when (AppConfig.themeMode) { "1", "3" -> "light"; "2" -> "dark"; else -> "system" })
                "navigation.close" -> { model.cancel(); null }
                else -> invalidEditor("Unknown embedded UI method")
            }
        } }
        editor.setMethodCallHandler(::onMethodCall)
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
                observation?.cancel()
                if (closed) { sink.error("closed", "Source editor view is closed", null); return }
                observation = scope.launch { model.state.collect { state ->
                    try { sink.success(snapshot(state)) }
                    catch (_: Exception) { sink.error("invalid_state", "Could not read source editor state", null) }
                } }
            }
            override fun onCancel(arguments: Any?) { observation?.cancel(); observation = null }
        })
    }

    internal fun onMethodCall(call: MethodCall, result: MethodChannel.Result) = reply(result) {
        when (call.method) {
            "snapshot" -> snapshot(model.state.value)
            "command" -> { parseSourceEditorCommand(call.arguments).apply(model, onPaste); null }
            else -> invalidEditor("Unknown source editor method")
        }
    }

    private fun snapshot(state: BookSourceComposeState) = sourceEditorSnapshot(
        state.copy(keyboardRows = state.keyboardRows ?: AppConfig.showBoardLine),
        maxLines = AppConfig.sourceEditMaxLine,
    ) {
        it.label ?: if (it.labelResource != 0) context.getString(it.labelResource) else it.key
    }

    private inline fun reply(result: MethodChannel.Result, block: () -> Any?) {
        if (closed) { result.error("closed", "Source editor view is closed", null); return }
        try { result.success(block()) }
        catch (error: SourceEditorInvalidCommand) { result.error("invalid_request", error.message, null) }
        catch (_: Exception) { result.error("editor_error", "Source editor operation failed", null) }
    }

    override fun close() {
        if (closed) return
        closed = true
        observation?.cancel()
        observation = null
        ui.setMethodCallHandler(null)
        editor.setMethodCallHandler(null)
        events.setStreamHandler(null)
        scope.cancel()
    }
}

internal class SourceEditorInvalidCommand(message: String) : IllegalArgumentException(message)
private fun invalidEditor(message: String): Nothing = throw SourceEditorInvalidCommand(message)
private const val MAX_EDITOR_TEXT = 8 * 1024 * 1024
private const val MAX_EDITOR_STATE = 16 * 1024 * 1024

/** Explicit codec-safe projection. No original JSON, native requests, paths or delivery IDs. */
internal fun sourceEditorSnapshot(
    state: BookSourceComposeState,
    maxLines: Int = Int.MAX_VALUE,
    label: (BookSourceEditField) -> String = { it.label ?: it.key },
): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>(
        "schemaVersion" to 1, "maxLines" to maxLines, "busy" to state.busy, "error" to sourceEditorSafeError(state.error),
        "confirmDiscard" to state.confirmDiscard, "groups" to state.groups,
        "variable" to state.variable, "variableComment" to state.variableComment,
        "keyboardRows" to state.keyboardRows,
        "assists" to state.assists.map { mapOf("key" to it.key, "value" to it.value) },
        "migrationAvailable" to state.migrationAvailable, "migrationRunning" to state.migrationRunning,
        "migrationError" to sourceEditorSafeError(state.migrationError),
        "migrationReport" to state.migrationReport?.let { report -> mapOf(
            "revision" to report.revision, "status" to report.preview.status,
            "requiresManualWork" to report.preview.requiresManualWork, "canApply" to report.preview.canApply,
            "issues" to report.preview.issues.map { mapOf("path" to it.path, "code" to it.code, "message" to sourceEditorSafeError(it.message)) }) },
        "document" to state.document?.let { doc -> mapOf(
            "revision" to doc.revision, "selectedTab" to doc.selectedTab, "focusedKey" to doc.focusedKey,
            "optionsExpanded" to doc.optionsExpanded, "autoComplete" to doc.autoComplete,
            "finished" to doc.finished, "dirty" to doc.dirty(), "hasLogin" to doc.form.hasLogin(),
            "form" to mapOf("options" to doc.form.options.let { mapOf(
                "enabled" to it.enabled, "enabledExplore" to it.enabledExplore,
                "enabledCookieJar" to it.enabledCookieJar, "enabledReview" to it.enabledReview,
                "type" to it.type, "eventListener" to it.eventListener, "customButton" to it.customButton) },
                "tabs" to doc.form.tabs.map { fields -> fields.map {
                    sourceEditorFieldSnapshot(it, label(it))
                } })
        ) },
    )
    // Stop as soon as the wire budget is exhausted, without allocating UTF-8 copies.
    requireSourceEditorBudget(result)
    return result
}

/** Long rules remain exclusively in the private draft/native full-screen editor. */
internal fun sourceEditorFieldSnapshot(field: BookSourceEditField, label: String): Map<String, Any?> {
    val external = field.value.length > 12000
    return mapOf(
        "key" to field.key, "label" to label,
        "external" to external, "originalLength" to field.value.length,
        "value" to if (external) "" else field.value,
        "selectionStart" to if (external) 0 else field.selectionStart,
        "selectionEnd" to if (external) 0 else field.selectionEnd,
    )
}

private const val PRIVATE_EDITOR_ERROR = "私有编辑数据读写失败"
private const val SESSION_UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private val privateAndroidPath = Regex(
    """(?<![A-Za-z0-9:/])(?:/data/(?:user(?:_de)?/[0-9]+|data)/[^/\s]+|""" +
        """/mnt/expand/[^/\s]+/user(?:_de)?/[0-9]+/[^/\s]+)/""" +
        """(?:files|cache|code_cache|no_backup|databases|shared_prefs)(?:/[^\s"'<>():,;]*)?""",
)
private val editorSessionPath = Regex(
    """(?<![A-Za-z0-9:/])(?:/[^\s"'<>():,;]*/)?(?:book-source-edit-drafts/""" + SESSION_UUID +
        """(?:-save)?\.json(?:\.bak|\.new)?|code-text-""" + SESSION_UUID + """\.txt)""",
)

/** Keep useful parser/network text, but never transport private draft/transfer identities. */
internal fun sourceEditorSafeError(message: String?): String? = message
    ?.replace(privateAndroidPath, PRIVATE_EDITOR_ERROR)
    ?.replace(editorSessionPath, PRIVATE_EDITOR_ERROR)

/** Mirrors Java's UTF-8 encoder: unpaired UTF-16 surrogates are replaced with one '?' byte. */
internal fun sourceEditorUtf8Size(value: String, limit: Long = Long.MAX_VALUE): Long {
    var bytes = 0L
    var index = 0
    while (index < value.length) {
        val code = value[index].code
        bytes += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            code in 0xD800..0xDBFF && index + 1 < value.length &&
                value[index + 1].code in 0xDC00..0xDFFF -> { index++; 4 }
            code in 0xD800..0xDFFF -> 1
            else -> 3
        }
        if (bytes > limit) return bytes
        index++
    }
    return bytes
}

internal fun requireSourceEditorBudget(value: Any?, limit: Long = MAX_EDITOR_STATE.toLong()) {
    var remaining = limit
    fun debit(bytes: Long) {
        remaining -= bytes
        if (remaining < 0) invalidEditor("Source editor state exceeds 16 MiB")
    }
    fun visit(item: Any?) {
        debit(8)
        when (item) {
            is String -> {
                // Every UTF-16 code unit costs at least one encoded byte except a pair,
                // which still costs four bytes, so length is a cheap lower bound.
                if (item.length.toLong() > remaining) invalidEditor("Source editor state exceeds 16 MiB")
                debit(sourceEditorUtf8Size(item, remaining))
            }
            is Map<*, *> -> item.entries.forEach { visit(it.key); visit(it.value) }
            is List<*> -> item.forEach(::visit)
        }
    }
    visit(value)
}

internal data class SourceEditorCommand(
    val op: String, val tab: Int = 0, val key: String = "", val text: String? = null,
    val start: Int = 0, val end: Int = 0, val value: Boolean = false,
    val options: BookSourceEditOptions? = null, val save: BookSourceSaveAction? = null,
    val native: BookSourceNativeAction? = null,
) {
    fun apply(model: BookSourceComposeViewModel, onPaste: () -> Unit) {
        if (op == "field" || op == "focus") {
            if (model.state.value.document?.form?.field(tab, key) == null) invalidEditor("Unknown editor field")
        }
        when (op) {
            "field" -> model.updateField(tab, key, requireNotNull(text), start, end)
            "focus" -> model.focus(tab, key)
            "tab" -> model.tab(tab)
            "options" -> model.options(requireNotNull(options))
            "expanded" -> model.optionsExpanded(value)
            "save" -> model.save(requireNotNull(save))
            "native" -> model.requestAction(requireNotNull(native), text)
            "autoComplete" -> model.autoComplete()
            "paste" -> onPaste()
            "clearCookie" -> model.clearCookie()
            "insert" -> model.insert(requireNotNull(text))
            "undo" -> model.undo()
            "redo" -> model.undo(redo = true)
            "groups" -> model.groups()
            "dismissGroups" -> model.dismissGroups()
            "variableEdit" -> model.variableEdit(requireNotNull(text))
            "variableSave" -> model.variable(text)
            "dismissVariable" -> model.dismissVariable()
            "cancel" -> model.cancel()
            "discard" -> model.discard()
            "keepEditing" -> model.keepEditing()
            "retry" -> model.retry()
            "migration" -> model.previewMigration()
            "dismissMigration" -> model.dismissMigration()
            "applyMigration" -> model.applyMigration()
        }
    }
}

/** Fully validate the command before any ViewModel mutation or native side effect. */
internal fun parseSourceEditorCommand(arguments: Any?): SourceEditorCommand {
    val args = arguments as? Map<*, *> ?: invalidEditor("Command must be a map")
    fun text(name: String, nullable: Boolean = false): String? {
        val value = args[name]
        if (nullable && value == null) return null
        val string = value as? String ?: invalidEditor("Invalid string: $name")
        if (string.length > MAX_EDITOR_TEXT || string.toByteArray(Charsets.UTF_8).size > MAX_EDITOR_TEXT)
            invalidEditor("Editor text exceeds 8 MiB")
        return string
    }
    fun integer(name: String) = args[name] as? Int ?: invalidEditor("Invalid integer: $name")
    fun tab(name: String) = integer(name).also { if (it !in 0..6) invalidEditor("Unknown editor tab") }
    val op = requireNotNull(text("op"))
    val fields = when (op) {
        "field" -> setOf("op", "tab", "key", "value", "start", "end")
        "focus" -> setOf("op", "tab", "key")
        "tab" -> setOf("op", "index")
        "options", "expanded", "variableEdit", "variableSave" -> setOf("op", "value")
        "save" -> setOf("op", "action")
        "native" -> setOf("op", "action", "text")
        "insert" -> setOf("op", "text")
        "autoComplete", "paste", "clearCookie", "undo", "redo", "groups", "dismissGroups",
        "dismissVariable", "cancel", "discard", "keepEditing", "retry", "migration",
        "dismissMigration", "applyMigration" -> setOf("op")
        else -> invalidEditor("Unknown editor operation")
    }
    if (args.keys.any { it !in fields }) invalidEditor("Unexpected command field")
    return when (op) {
        "field", "focus" -> {
            val key = requireNotNull(text("key"))
            if (key.isBlank() || key.length > 128) invalidEditor("Invalid field key")
            val value = if (op == "field") requireNotNull(text("value")) else null
            val start = if (op == "field") integer("start") else 0
            val end = if (op == "field") integer("end") else 0
            if (value != null && (start !in 0..value.length || end !in 0..value.length)) invalidEditor("Invalid selection")
            SourceEditorCommand(op, tab("tab"), key, value, start, end)
        }
        "tab" -> SourceEditorCommand(op, tab = tab("index"))
        "expanded" -> SourceEditorCommand(op, value = args["value"] as? Boolean ?: invalidEditor("Invalid Boolean"))
        "options" -> {
            val values = args["value"] as? Map<*, *> ?: invalidEditor("Options must be a map")
            val names = setOf("enabled", "enabledExplore", "enabledCookieJar", "enabledReview", "type", "eventListener", "customButton")
            if (values.keys != names) invalidEditor("Options must contain all seven fields")
            fun flag(name: String) = values[name] as? Boolean ?: invalidEditor("Invalid option: $name")
            val type = values["type"] as? Int ?: invalidEditor("Invalid source type")
            if (type !in 0..4) invalidEditor("Unknown source type")
            SourceEditorCommand(op, options = BookSourceEditOptions(flag("enabled"), flag("enabledExplore"),
                flag("enabledCookieJar"), flag("enabledReview"), type, flag("eventListener"), flag("customButton")))
        }
        "save" -> SourceEditorCommand(op, save = BookSourceSaveAction.entries.firstOrNull { it.name == text("action") }
            ?: invalidEditor("Unknown save action"))
        "native" -> SourceEditorCommand(op, native = BookSourceNativeAction.entries.firstOrNull { it.name == text("action") }
            ?: invalidEditor("Unknown native action"), text = text("text", nullable = true))
        "insert" -> SourceEditorCommand(op, text = text("text"))
        "variableEdit" -> SourceEditorCommand(op, text = text("value"))
        "variableSave" -> {
            if (!args.containsKey("value")) invalidEditor("Missing variable value")
            SourceEditorCommand(op, text = text("value", nullable = true))
        }
        else -> SourceEditorCommand(op)
    }
}
