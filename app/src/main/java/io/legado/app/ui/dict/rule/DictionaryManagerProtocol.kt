package io.legado.app.ui.dict.rule

import io.legado.app.help.SourceSharePassphrase

/** Explicit projection: dictionary definitions and native effects never cross the presentation channel. */
internal fun dictionaryManagerSnapshot(state: DictionaryRuleManagementUiState, binding: String, revision: Long): Map<String, Any?> = linkedMapOf(
    "schemaVersion" to 1, "binding" to binding, "revision" to revision,
    "loading" to state.loading, "busy" to state.busy, "error" to (state.error != null),
    "rules" to state.rules.map { mapOf("name" to it.name, "enabled" to it.enabled) },
    "selected" to state.selected.toList(), "deleteName" to state.deleteName,
    "online" to state.online, "onlineInput" to state.onlineInput, "history" to state.history,
    "exportUrl" to state.exportUrl, "exportSummary" to state.exportSummary,
    "canEncode" to (state.exportUrl?.let(SourceSharePassphrase::canEncode) ?: false),
    "passphrase" to state.passphrase,
)

internal fun dictionaryManagerCommand(args: Map<*, *>, model: DictionaryRuleManagementViewModel, native: (String, String?) -> Unit) {
    val op = args["op"] as? String ?: error("Missing operation")
    fun text(key: String): String = (args[key] as? String)?.also { require(it.length <= 1024 * 1024) }
        ?: throw IllegalArgumentException("Invalid text")
    fun name(key: String = "name"): String = text(key).also { value -> require(model.state.value.rules.any { it.name == value }) }
    fun flag(): Boolean = args["value"] as? Boolean ?: throw IllegalArgumentException("Invalid flag")
    val fields = when (op) {
        "toggle", "enabled", "edit", "delete", "beginSlide", "slideTo" -> if (op == "enabled") setOf("op", "name", "value") else setOf("op", "name")
        "input", "online", "deleteHistory", "enableSelection" -> setOf("op", "value")
        "move" -> setOf("op", "from", "to")
        else -> setOf("op")
    }
    require(args.keys == fields)
    val cancel = op in setOf("cancelSlide", "cancelReorder", "back", "cancelDelete", "closeExport", "closePassphrase") || (op == "online" && args["value"] == false)
    require(cancel || (!model.state.value.busy && (!model.state.value.loading || op == "retry")))
    when (op) {
        "back", "add", "importFile", "importQr", "help" -> native(op, null)
        "edit" -> native(op, name())
        "toggle" -> model.toggle(name())
        "selectAll" -> model.selectAll()
        "invert" -> model.invert()
        "enabled" -> model.setEnabled(name(), flag())
        "enableSelection" -> model.enableSelection(flag())
        "delete" -> model.requestDelete(name())
        "cancelDelete" -> model.requestDelete(null)
        "confirmDelete" -> model.confirmDelete()
        "deleteSelection" -> model.deleteSelection()
        "share" -> model.share()
        "export" -> model.export()
        "importDefault" -> model.importDefault()
        "online" -> model.showOnline(flag())
        "input" -> { require(model.state.value.online); model.inputOnline(text("value")) }
        "deleteHistory" -> { val url = text("value"); require(url in model.state.value.history); model.deleteHistory(url) }
        "confirmOnline" -> { require(model.state.value.online); model.confirmOnline() }
        "closeExport" -> model.closeExport()
        "copyExport" -> model.copyExport()
        "createPassphrase" -> { require(model.state.value.exportUrl?.let(SourceSharePassphrase::canEncode) == true); model.createPassphrase() }
        "closePassphrase" -> model.closePassphrase()
        "copyPassphrase" -> model.copyPassphrase()
        "beginSlide" -> model.beginSlide(name())
        "slideTo" -> model.slideTo(name())
        "endSlide" -> model.endSlide()
        "cancelSlide" -> model.cancelSlide()
        "move" -> model.move(name("from"), name("to"))
        "finishReorder" -> model.finishReorder()
        "cancelReorder" -> model.cancelReorder()
        "retry" -> model.retry()
        else -> throw IllegalArgumentException("Unknown operation")
    }
}
