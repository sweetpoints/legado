package io.legado.app.ui.book.toc.rule

internal fun txtTocManagerSnapshot(s: TxtTocRuleManagementUiState, binding: String, revision: Long, picker: Boolean, labels: Map<String, String>): Map<String, Any?> = mapOf(
    "schemaVersion" to 1, "binding" to binding, "revision" to revision,
    "loaded" to !s.loading, "busy" to s.busy, "error" to (s.error != null), "picker" to picker,
    "rows" to s.visible.map { mapOf("id" to it.id, "name" to it.name, "example" to it.example, "enabled" to it.enable, "order" to it.serialNumber) },
    "selected" to s.selected.toList(), "selectedId" to s.selectedId, "pickerFinished" to s.pickerFinished,
    "query" to s.query, "queryStart" to s.queryStart, "queryEnd" to s.queryEnd,
    "onlineInput" to s.onlineInput, "onlineInputStart" to s.onlineStart, "onlineInputEnd" to s.onlineEnd,
    "deleteIds" to s.deleteIds, "deleteNames" to s.rules.filter { it.id in s.deleteIds }.map { it.name },
    "online" to s.online, "history" to s.history, "exportUrl" to s.exportUrl, "exportSummary" to s.exportSummary,
    "canEncode" to (s.exportUrl?.let(io.legado.app.help.SourceSharePassphrase::canEncode) == true),
    "passphrase" to s.passphrase, "scrollIndex" to s.scrollIndex, "scrollOffset" to s.scrollOffset,
    "labels" to labels,
)

internal fun txtTocManagerCommand(a: Map<*, *>, model: TxtTocRuleManagementViewModel, native: (String, Long?) -> Unit, back: () -> Unit) {
    val op = a["op"] as? String ?: error("Missing operation")
    fun number(key: String): Long = when(val v = a[key]) { is Long -> v; is Int -> v.toLong(); else -> error("Invalid number") }
    fun id(key: String = "id") = number(key).also { require(model.state.value.rules.any { r -> r.id == it }) }
    fun flag() = a["value"] as? Boolean ?: error("Invalid flag")
    val keys = when(op) {
        "query", "onlineInput" -> setOf("op", "text", "start", "end")
        "toggle", "choose", "edit", "delete", "beginSlide", "slideTo", "beginDrag" -> setOf("op", "id")
        "move" -> setOf("op", "from", "to")
        "enabled", "edge" -> setOf("op", "id", "value")
        "enableSelection", "online" -> setOf("op", "value")
        "deleteHistory" -> setOf("op", "text")
        "scroll" -> setOf("op", "index", "offset")
        else -> setOf("op")
    }
    require(a.keys == keys)
    val s = model.state.value
    require(op in setOf("back", "cancelGesture", "cancelModal", "cancelQuery", "retry", "scroll") || (model.privateStateReady && !s.loading && !s.busy && !s.pickerFinished))
    when(op) {
        "back" -> back()
        "cancelModal" -> when { s.deleteIds.isNotEmpty() -> model.cancelDelete(); s.online -> model.showOnline(false); s.exportUrl != null -> model.closeExport(); s.passphrase != null -> model.closePassphrase() }
        "query", "onlineInput" -> {
            val text = a["text"] as? String ?: error("Invalid text"); val start = number("start"); val end = number("end")
            require(start in 0..text.length.toLong() && end in 0..text.length.toLong())
            if (op == "query") { model.inputQuery(text); model.querySelection(start.toInt(), end.toInt()) }
            else { require(s.online); model.inputOnline(text); model.onlineSelection(start.toInt(), end.toInt()) }
        }
        "cancelQuery" -> Unit
        "toggle" -> model.toggle(id())
        "choose" -> model.choose(id())
        "confirmChoice" -> model.confirmChoice()
        "all" -> model.selectAll()
        "invert" -> model.invert()
        "enabled" -> model.setEnabled(id(), flag())
        "enableSelection" -> model.enableSelection(flag())
        "delete" -> model.requestDelete(listOf(id()))
        "deleteSelection" -> model.deleteSelection()
        "confirmDelete" -> model.confirmDelete()
        "edge" -> model.toEdge(id(), flag())
        "default" -> model.importDefault()
        "share" -> model.share()
        "export" -> model.export()
        "online" -> model.showOnline(flag())
        "deleteHistory" -> { val text = a["text"] as? String ?: error("Invalid text"); require(text in s.history); model.deleteHistory(text) }
        "confirmOnline" -> model.confirmOnline()
        "copyExport" -> model.copyExport()
        "passphrase" -> model.createPassphrase()
        "copyPassphrase" -> model.copyPassphrase()
        "beginDrag" -> { id(); require(s.query.isBlank()) }
        "beginSlide" -> model.beginSlide(id())
        "slideTo" -> model.slideTo(id())
        "finishSlide" -> model.endSlide()
        "move" -> { require(s.query.isBlank()); model.move(id("from"), id("to")) }
        "finishDrag" -> model.finishReorder()
        "cancelGesture" -> model.cancelGestures()
        "retry" -> model.retry()
        "scroll" -> { val index = number("index"); val offset = number("offset"); require(index in 0..Int.MAX_VALUE.toLong() && offset in 0..Int.MAX_VALUE.toLong()); model.scroll(index.toInt(), offset.toInt()) }
        "add", "importFile", "importQr", "help" -> native(op, null)
        "edit" -> native(op, id())
        else -> error("Unknown operation")
    }
}
