package io.legado.app.ui.rss.source.manage

import io.legado.app.utils.GSON

/** Explicit wire fields: no Room entity, export path, nonce or session identifier crosses to Dart. */
internal fun rssManagerSnapshot(
    state: RssSourceManagementState,
    binding: String? = null,
    revision: Long? = null,
): String =
    GSON.newBuilder().serializeNulls().create().toJson(linkedMapOf(
        "schemaVersion" to 1, "binding" to binding, "revision" to revision,
        "loaded" to state.loaded, "busy" to state.busy,
        "dragging" to state.dragging, "pending" to (state.pending != null), "waitingNative" to state.waitingNative,
        "rows" to state.rows.map { row -> linkedMapOf(
            "id" to row.id, "name" to row.displayName, "group" to row.group,
            "enabled" to row.enabled,
        ) },
        "groups" to state.groups, "selected" to state.selected.toList(),
        "query" to state.query, "queryStart" to state.queryStart, "queryEnd" to state.queryEnd,
        "dialog" to state.dialog?.name, "draft" to state.draft,
        "draftStart" to state.draftStart, "draftEnd" to state.draftEnd,
        "targets" to state.targets, "history" to state.history,
        "feedback" to state.feedback?.let { linkedMapOf("summary" to it.summary, "canEncode" to it.canEncode) },
        "error" to (state.error != null), "scrollIndex" to state.scrollIndex,
        "scrollOffset" to state.scrollOffset,
    )).also { require(it.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024) }

/** Validate the whole command before applying one operation on Main. */
internal fun rssManagerCommand(arguments: Any?, model: RssSourceManagementViewModel, back: () -> Unit) {
    val args = arguments as? Map<*, *> ?: throw IllegalArgumentException("Invalid command")
    fun text(key: String): String = (args[key] as? String)?.also {
        require(it.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
    } ?: throw IllegalArgumentException("Invalid text")
    fun number(key: String): Int = (args[key] as? Int)?.also { require(it >= 0) }
        ?: throw IllegalArgumentException("Invalid number")
    fun flag(key: String): Boolean = args[key] as? Boolean ?: throw IllegalArgumentException("Invalid flag")
    fun ids(): List<String> {
        val values = args["ids"] as? List<*> ?: throw IllegalArgumentException("Invalid ids")
        require(values.size <= 10000)
        val result = values.map { (it as? String)?.also { id -> require(id.matches(Regex("[0-9a-f]{64}"))) }
            ?: throw IllegalArgumentException("Invalid id") }
        require(args["op"] == "selectionRange" || result.distinct().size == result.size)
        val visible = model.state.value.rows.mapTo(HashSet()) { it.id }
        require(result.all { it in visible })
        return result
    }
    val op = text("op")
    val fields = when (op) {
        "query", "draft" -> setOf("op", "value", "start", "end")
        "selected" -> setOf("op", "ids", "checked")
        "enabled", "edge" -> setOf("op", "ids", "value")
        "dialog" -> setOf("op", "value", "ids")
        "effect" -> setOf("op", "value", "ids")
        "forgetImport", "filter" -> setOf("op", "value")
        "move", "dragTo" -> setOf("op", "ids", "after")
        "beginDrag", "selectionRange" -> setOf("op", "ids")
        "scroll" -> setOf("op", "index", "offset")
        "back", "all", "invert", "interval", "cancel", "confirm", "defaults", "passphrase", "copy", "retry", "finishDrag", "cancelGesture", "beginSelection", "finishSelection" -> setOf("op")
        else -> throw IllegalArgumentException("Unknown command")
    }
    require(args.keys == fields)
    when (op) {
        "query", "draft" -> {
            val value = text("value"); val start = number("start"); val end = number("end")
            require(start <= value.length && end <= value.length)
            if (op == "query") model.query(value, start, end) else model.draft(value, start, end)
        }
        "selected" -> { val ids = ids(); require(ids.size == 1); val checked = flag("checked"); model.selected(ids.single(), checked) }
        "enabled" -> { val ids = ids(); val value = flag("value"); model.enabled(ids, value) }
        "edge" -> { val ids = ids(); val value = flag("value"); model.edge(ids, value) }
        "dialog" -> { val value = RssSourceManagementDialog.valueOf(text("value")); val ids = ids();
            require(value in listOf(RssSourceManagementDialog.Delete,RssSourceManagementDialog.AddGroup,RssSourceManagementDialog.RemoveGroup,RssSourceManagementDialog.ImportUrl)); model.dialog(value, ids) }
        "effect" -> {
            val value = RssSourceManagementAction.valueOf(text("value")); val ids = ids()
            require(value !in listOf(RssSourceManagementAction.ImportInput, RssSourceManagementAction.Copy, RssSourceManagementAction.ImportUrl))
            require(if (value == RssSourceManagementAction.Edit) ids.size == 1 else ids.isEmpty())
            model.effect(value, ids.firstOrNull())
        }
        "move" -> { val ids = ids(); val after = flag("after"); require(ids.size == 2)
            if (model.beginDrag(ids[0])) { model.dragTo(ids[1], after); model.finishDrag() }
        }
        "beginSelection" -> require(model.beginSelection())
        "selectionRange" -> { val ids = ids(); require(ids.size == 2); model.selectionRange(ids[0], ids[1]) }
        "finishSelection" -> model.finishSelection()
        "beginDrag" -> { val ids = ids(); require(ids.size == 1); require(model.beginDrag(ids.single())) }
        "dragTo" -> { val ids = ids(); val after = flag("after"); require(ids.size == 1); model.dragTo(ids.single(), after) }
        "finishDrag" -> model.finishDrag()
        "cancelGesture" -> model.cancelGesture()
        "scroll" -> { val index = number("index"); val offset = number("offset"); model.scroll(index, offset) }
        "filter" -> model.filterKey(text("value"))
        "forgetImport" -> model.forgetImport(text("value"))
        "back" -> back()
        "all" -> model.selectAll()
        "invert" -> model.invertSelection()
        "interval" -> model.selectInterval()
        "cancel" -> model.cancelDialog()
        "confirm" -> model.confirmDialog()
        "defaults" -> model.defaults()
        "passphrase" -> model.passphrase()
        "copy" -> model.copyFeedback()
        "retry" -> model.retry()
    }
}
