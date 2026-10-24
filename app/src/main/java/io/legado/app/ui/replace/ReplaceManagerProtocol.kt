package io.legado.app.ui.replace

/** Narrow presentation projection; native effects, import handles and export paths stay native. */
internal fun replaceManagerSnapshot(state: ReplaceManagementState, binding: String, revision: Long, labels: Map<String, String>): Map<String, Any?> = mapOf(
    "schemaVersion" to 1, "binding" to binding, "revision" to revision,
    "loaded" to state.loaded, "busy" to state.busy, "pending" to (state.pending != null),
    "waitingNative" to state.waitingNative, "error" to (state.error != null),
    "rows" to state.rows.map { mapOf("id" to it.id, "name" to it.name, "displayName" to it.displayName, "group" to it.group, "enabled" to it.enabled, "order" to it.order) },
    "groups" to state.groups, "selected" to state.selected.toList(), "query" to state.query,
    "queryStart" to state.queryStart, "queryEnd" to state.queryEnd,
    "dialog" to state.dialog?.name, "targets" to state.targets,
    "draft" to state.draft, "draftStart" to state.draftStart, "draftEnd" to state.draftEnd,
    "manual" to state.manual, "dragging" to state.dragging, "history" to state.history,
    "summary" to state.feedback?.summary, "canEncode" to (state.feedback?.canEncode == true),
    "scrollIndex" to state.scrollIndex, "scrollOffset" to state.scrollOffset, "labels" to labels,
)

internal fun replaceManagerCommand(args: Map<*, *>, model: ReplaceManagementViewModel, back: () -> Unit) {
    val op = args["op"] as? String ?: error("Missing operation")
    fun number(key: String): Long = when (val value = args[key]) { is Long -> value; is Int -> value.toLong(); else -> error("Invalid number") }
    fun text(key: String) = (args[key] as? String) ?: error("Invalid text")
    fun flag(key: String = "value") = args[key] as? Boolean ?: error("Invalid flag")
    fun id(key: String = "id") = number(key).also { value -> require(model.state.value.rows.any { it.id == value }) }
    fun ids(): List<Long> = (args["ids"] as? List<*>)?.map { when(it) { is Long -> it; is Int -> it.toLong(); else -> error("Invalid id") } }?.also { values ->
        require(values.distinct().size == values.size && values.all { value -> model.state.value.rows.any { it.id == value } })
    } ?: error("Missing ids")
    val keys = when (op) {
        "query", "draft" -> setOf("op", "text", "start", "end")
        "selected" -> setOf("op", "id", "value")
        "enabled", "edge" -> setOf("op", "ids", "value")
        "dialog" -> setOf("op", "kind", "ids")
        "effect" -> setOf("op", "action", "id")
        "forgetImport" -> setOf("op", "text")
        "selectionRange" -> setOf("op", "first", "last")
        "beginDrag" -> setOf("op", "id")
        "dragTo" -> setOf("op", "id", "after")
        "scroll" -> setOf("op", "index", "offset")
        else -> setOf("op")
    }
    require(args.keys == keys)
    val state = model.state.value
    require(op in setOf("back", "cancelGesture", "cancelDialog", "retry", "scroll") || (state.loaded && !state.busy && state.pending == null))
    when (op) {
        "back" -> back()
        "query", "draft" -> {
            val value = text("text"); val start = number("start"); val end = number("end")
            require(start in 0..value.length.toLong() && end in 0..value.length.toLong())
            if (op == "query") model.query(value, start.toInt(), end.toInt()) else { require(state.dialog != null); model.draft(value, start.toInt(), end.toInt()) }
        }
        "cancelQuery" -> model.query(state.query, state.queryStart, state.queryEnd)
        "selected" -> model.selected(id(), flag())
        "all" -> model.selectAll()
        "invert" -> model.invertSelection()
        "interval" -> model.selectInterval()
        "enabled" -> model.enabled(ids(), flag())
        "edge" -> model.edge(ids(), flag())
        "dialog" -> model.dialog(ReplaceManagementDialog.valueOf(text("kind")), ids())
        "cancelDialog" -> model.cancelDialog()
        "confirmDialog" -> model.confirmDialog()
        "manual" -> model.toggleManual()
        "effect" -> {
            val action = ReplaceManagementAction.valueOf(text("action"))
            require(action !in setOf(ReplaceManagementAction.ImportInput, ReplaceManagementAction.Copy, ReplaceManagementAction.ImportUrl))
            val rule = args["id"]?.let { id() }; require(action != ReplaceManagementAction.Edit || rule != null)
            model.effect(action, rule)
        }
        "forgetImport" -> { val value = text("text"); require(value in state.history); model.forgetImport(value) }
        "passphrase" -> { require(state.feedback?.canEncode == true); model.passphrase() }
        "copy" -> model.copyFeedback()
        "retry" -> model.retry()
        "beginSelection" -> check(model.beginSelection())
        "selectionRange" -> model.selectionRange(id("first"), id("last"))
        "finishSelection" -> model.finishSelection()
        "beginDrag" -> check(model.beginDrag(id()))
        "dragTo" -> model.dragTo(id(), flag("after"))
        "finishDrag" -> model.finishDrag()
        "cancelGesture" -> model.cancelGesture()
        "scroll" -> { val index = number("index"); val offset = number("offset"); require(index in 0..Int.MAX_VALUE.toLong() && offset in 0..Int.MAX_VALUE.toLong()); model.scroll(index.toInt(), offset.toInt()) }
        else -> error("Unknown operation")
    }
}
