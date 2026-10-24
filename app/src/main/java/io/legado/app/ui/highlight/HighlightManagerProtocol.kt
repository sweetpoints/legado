package io.legado.app.ui.highlight

import io.legado.app.data.repository.HighlightManagementAction

/** Immutable presentation projection: executable patterns, exports and private rule bodies stay native. */
internal fun highlightManagerSnapshot(s: HighlightManagementState, binding: String, revision: Long): Map<String, Any?> = mapOf(
    "schemaVersion" to 1, "binding" to binding, "revision" to revision,
    "loaded" to (s.loaded && s.rowsReady), "busy" to s.busy, "closed" to s.closed,
    "error" to s.error, "filter" to s.draft.filter, "groups" to s.groups,
    "scrollIndex" to s.draft.scrollIndex, "scrollOffset" to s.draft.scrollOffset,
    "selected" to s.draft.selection.toList(), "deletion" to s.draft.deletion.toList(),
    "deletionName" to s.draft.deletionName, "exportResult" to s.draft.exportResult,
    "exporting" to (s.draft.exporting != null || s.draft.effects.any { it.action == HighlightManagementAction.Export }),
    "rows" to s.visible.map { mapOf("id" to it.id, "uuid" to it.uuid, "name" to it.displayName,
        "label" to it.label, "enabled" to it.isEnabled, "order" to it.order) },
)

internal class HighlightManagerCommands(private val model: HighlightManagementViewModel) {
    private var anchor: String? = null
    suspend fun execute(op: String, a: Map<*, *>) {
        fun uuid() = (a["uuid"] as? String).also { require(it in model.state.value.visible.map { r -> r.uuid }) }!!
        fun flag() = a["value"] as? Boolean ?: error("Missing flag")
        when (op) {
            "back" -> {
                if (model.state.value.draft.deletion.isNotEmpty()) model.dismissDelete()
                else if (model.state.value.draft.exportResult != null) model.dismissExportResult()
                else model.close()
            }
            "cancelGesture" -> { anchor = null; model.cancelGesture() }
            "retry" -> model.retry()
            "scroll" -> model.scroll((a["index"] as Number).toInt(), (a["offset"] as Number).toDouble())
            else -> {
                require(model.state.value.canAct)
                when (op) {
                    "action" -> {
                        val action = HighlightManagementAction.valueOf(a["action"] as String)
                        require(action in setOf(HighlightManagementAction.Add, HighlightManagementAction.Edit, HighlightManagementAction.Import, HighlightManagementAction.Groups))
                        val id = (a["id"] as? Number)?.toLong()
                        if (action == HighlightManagementAction.Edit) require(model.state.value.rules.any { it.id == id })
                        model.action(action, id)
                    }
                    "select" -> model.select(uuid())
                    "all" -> model.selectAll(flag())
                    "enable" -> model.enable(uuid(), flag())
                    "enableSelection" -> model.enableSelection(flag())
                    "moveEdge" -> model.move(uuid(), flag())
                    "moveSelection" -> model.moveSelection(flag())
                    "delete" -> model.requestDelete(if (a["uuid"] == null) null else uuid())
                    "deleteConfirm" -> model.deleteConfirmed()
                    "deleteCancel" -> model.dismissDelete()
                    "filter" -> { val group = a["value"] as String?; require(group == null || group == HighlightManagementState.UNGROUPED || group in model.state.value.groups); model.filter(group) }
                    "export" -> model.export(flag())
                    "share" -> model.share()
                    "exportDismiss" -> model.dismissExportResult()
                    "exportCopy" -> model.copyExportResult()
                    "dragStart" -> model.beginReorder()
                    "dragMove" -> { val from = uuid(); val to = a["to"] as String; require(model.state.value.visible.any { it.uuid == to }); model.reorder(from, to) }
                    "dragEnd" -> model.finishReorder()
                    "slideStart" -> { anchor = uuid(); model.beginRange(); model.range(setOf(anchor!!)) }
                    "slideMove" -> { val rows = model.state.value.visible; val from = rows.indexOfFirst { it.uuid == anchor }; val to = rows.indexOfFirst { it.uuid == uuid() }; require(from >= 0 && to >= 0); model.range(rows.subList(minOf(from, to), maxOf(from, to) + 1).mapTo(linkedSetOf()) { it.uuid }) }
                    "slideEnd" -> { anchor = null; model.finishRange() }
                    else -> error("Unknown command")
                }
            }
        }
        model.flush()
    }
}
