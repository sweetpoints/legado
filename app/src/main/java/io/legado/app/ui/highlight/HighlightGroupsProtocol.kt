package io.legado.app.ui.highlight

internal fun highlightGroupsSnapshot(s: HighlightGroupState, binding: String, revision: Long) = mapOf(
    "schemaVersion" to 1, "binding" to binding, "revision" to revision,
    "loaded" to !s.loading, "busy" to (s.busy || s.refresh), "error" to s.error,
    "groups" to s.groups, "stage" to s.stage.name, "source" to s.source, "name" to s.name,
    "nameStart" to s.nameStart, "nameEnd" to s.nameEnd,
)
internal fun highlightGroupsCommand(model: HighlightGroupViewModel, op: String, a: Map<*, *>, close: () -> Unit) {
    when (op) {
        "back" -> if (!model.state.value.busy && !model.state.value.refresh) {
            if (model.state.value.stage != HighlightGroupStage.None) model.cancel() else close()
        }
        "cancel" -> model.cancel()
        "retry" -> model.retry()
        else -> {
            require(!model.state.value.busy && !model.state.value.loading && !model.state.value.refresh)
            when (op) {
                "rename" -> model.rename(a["value"] as String)
                "delete" -> model.delete(a["value"] as String)
                "name" -> model.rawName(a["text"] as String, (a["start"] as Number).toInt(), (a["end"] as Number).toInt())
                "confirmRename" -> model.confirmRename()
                "confirmDelete" -> model.confirmDelete()
                "chooseMove" -> model.chooseMove()
                "move" -> model.move(a["value"] as String?)
                else -> error("Unknown command")
            }
        }
    }
}
