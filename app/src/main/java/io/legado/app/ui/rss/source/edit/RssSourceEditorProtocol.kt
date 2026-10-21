package io.legado.app.ui.rss.source.edit

import io.legado.app.data.repository.*
import io.legado.app.ui.widget.code.EditSafety

/** UI projection only: no draft path, result token, original key or exported source payload. */
internal fun rssSourceEditorSnapshot(
    state: RssSourceEditorState,
    generation: Int,
    label: (RssSourceEditorField) -> String,
    tabLabels: List<String>,
    typeLabels: List<String>,
    articleStyleLabels: List<String>,
    assists: List<RssSourceEditorAssist> = emptyList(),
    keyboardRows: Int = 1,
    maxLines: Int = 6,
): Map<String, Any?> = linkedMapOf(
    "schemaVersion" to 1, "generation" to generation,
    "loading" to state.loading, "loaded" to state.loaded, "busy" to state.busy,
    "missing" to state.missing, "canEdit" to state.canEdit, "editorPending" to state.editorPending,
    "finished" to state.finished, "savedResult" to state.savedResult, "exit" to state.exit,
    "expanded" to state.expanded, "autoComplete" to state.autoComplete,
    "error" to rssEditorSafeError(state.error), "issue" to state.issue?.name, "tab" to state.tab,
    "focus" to state.focus?.key,
    "tabs" to (0..3).map { tab -> mapOf(
        "index" to tab, "label" to tabLabels[tab],
        "fields" to RssSourceEditorField.entries.filter { it.tab == tab }.map { field ->
            val value = state.draft[field].bounded()
            val external = EditSafety.isTooLongForInline(value.text) || EditSafety.isCombiningHeavy(value.text)
            mapOf("key" to field.key, "label" to label(field), "external" to external,
                "length" to value.text.length, "text" to if (external) null else value.text,
                "start" to if (external) 0 else value.start, "end" to if (external) 0 else value.end)
        }) },
    "options" to state.draft.let { mapOf(
        "enabled" to it.enabled, "singleUrl" to it.singleUrl, "cookieJar" to it.cookieJar,
        "preload" to it.preload, "enableJs" to it.enableJs, "loadWithBaseUrl" to it.loadWithBaseUrl,
        "showWebLog" to it.showWebLog, "cacheFirst" to it.cacheFirst,
        "type" to it.type, "articleStyle" to it.articleStyle,
    ) },
    "assists" to assists.map { mapOf("key" to it.key, "value" to it.value) },
    "keyboardRows" to keyboardRows.coerceIn(1, 5), "maxLines" to maxLines.coerceAtLeast(1),
    "typeLabels" to typeLabels, "articleStyleLabels" to articleStyleLabels,
).also(::requireRssEditorBudget)

internal data class RssEditorCommand(
    val op: String,
    val field: RssSourceEditorField? = null,
    val text: String? = null,
    val start: Int = 0,
    val end: Int = 0,
    val index: Int = 0,
    val key: String? = null,
    val value: Any? = null,
    val save: RssSourceEditorSaveAction? = null,
    val native: RssSourceEditorEffectKind? = null,
)

internal val rssEditorBooleanOptions = setOf("enabled", "singleUrl", "cookieJar", "preload",
    "enableJs", "loadWithBaseUrl", "showWebLog", "cacheFirst")
internal val rssEditorNativeActions = setOf(RssSourceEditorEffectKind.Clipboard,
    RssSourceEditorEffectKind.ScanQr, RssSourceEditorEffectKind.ShareText,
    RssSourceEditorEffectKind.ShareQr, RssSourceEditorEffectKind.Log, RssSourceEditorEffectKind.Help,
    RssSourceEditorEffectKind.JsHelp, RssSourceEditorEffectKind.RegexHelp,
    RssSourceEditorEffectKind.File, RssSourceEditorEffectKind.UrlOptions)

internal fun parseRssEditorCommand(arguments: Any?, generation: Int): RssEditorCommand {
    val args = arguments as? Map<*, *> ?: error("Command must be a map")
    require(args["generation"] == generation && args["generation"] is Int) { "Stale editor generation" }
    val op = args["op"] as? String ?: error("Missing operation")
    val keys = when (op) {
        "field" -> setOf("key", "value", "start", "end")
        "focus" -> setOf("key")
        "tab" -> setOf("index")
        "option" -> setOf("key", "value")
        "expanded", "autoComplete" -> setOf("value")
        "save", "native" -> setOf("action")
        "insert" -> setOf("text")
        "editor", "paste", "clearCookie", "undo", "redo", "cancel", "keepEditing", "discard",
        "retry", "retryEditor", "discardEditor", "keyboardConfig" -> emptySet()
        else -> error("Unknown operation")
    } + setOf("generation", "op")
    require(args.keys == keys) { "Unexpected or missing command fields" }
    fun text(key: String) = args[key] as? String ?: error("Invalid text")
    fun integer(key: String) = args[key] as? Int ?: error("Invalid integer")
    return when (op) {
        "field", "focus" -> {
            val field = RssSourceEditorField.entries.firstOrNull { it.key == text("key") }
                ?: error("Unknown field")
            if (op == "focus") RssEditorCommand(op, field = field)
            else {
                val value = text("value")
                val start = integer("start")
                val end = integer("end")
                require(start in 0..value.length && end in 0..value.length)
                require(!EditSafety.isTooLongForInline(value) && !EditSafety.isCombiningHeavy(value))
                RssEditorCommand(op, field = field, text = value, start = start, end = end)
            }
        }
        "tab" -> RssEditorCommand(op, index = integer("index").also { require(it in 0..3) })
        "expanded", "autoComplete" -> RssEditorCommand(op,
            value = args["value"] as? Boolean ?: error("Invalid Boolean"))
        "option" -> {
            val key = text("key")
            val value = args["value"]
            when (key) {
                in rssEditorBooleanOptions -> require(value is Boolean)
                "type" -> require(value is Int && value in 0..2)
                "articleStyle" -> require(value is Int && value in 0..4)
                else -> error("Unknown option")
            }
            RssEditorCommand(op, key = key, value = value)
        }
        "save" -> RssEditorCommand(op, save = RssSourceEditorSaveAction.entries.firstOrNull { it.name == text("action") }
            ?: error("Unknown save action"))
        "native" -> RssEditorCommand(op, native = rssEditorNativeActions.firstOrNull { it.name == text("action") }
            ?: error("Unknown native action"))
        "insert" -> RssEditorCommand(op, text = text("text"))
        else -> RssEditorCommand(op)
    }
}

/** Errors may originate in private draft/transfer IO. Never expose those paths or owner UUIDs. */
internal fun rssEditorSafeError(message: String?): String? {
    message ?: return null
    if (Regex("(?i)(?:/data/|/mnt/expand/|rss-source-editor/|code-text-|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})").containsMatchIn(message))
        return "Private editor data operation failed"
    return message.take(4096)
}

internal fun requireRssEditorBudget(value: Any?, limit: Long = 16L * 1024 * 1024) {
    var remaining = limit
    fun visit(item: Any?) {
        remaining -= 8
        require(remaining >= 0) { "Editor state exceeds wire budget" }
        when (item) {
            is String -> {
                // Conservative UTF-8 upper bound, without allocating another private payload.
                remaining -= item.length.toLong() * 3
                require(remaining >= 0) { "Editor state exceeds wire budget" }
            }
            is Map<*, *> -> item.forEach { (key, value) -> visit(key); visit(value) }
            is List<*> -> item.forEach(::visit)
        }
    }
    visit(value)
}
