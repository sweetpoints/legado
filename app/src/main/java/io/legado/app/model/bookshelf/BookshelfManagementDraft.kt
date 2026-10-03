package io.legado.app.model.bookshelf

internal enum class ShelfManagementAction { Delete, EnableUpdate, DisableUpdate, ChangeSource, ClearCache,
    PersistCovers, RestoreNetworkCovers, RestoreSourceCovers, UpdateToc, CreateTasks, ExportSources }
internal data class ShelfManagementConfirmation(val action: ShelfManagementAction, val ids: List<String>, val deleteOriginal: Boolean = false,
    val cron: String = "*/30 * * * *", val selectionStart: Int = cron.length, val selectionEnd: Int = selectionStart)
internal data class ShelfGroupRequest(val id: String, val ids: List<String>, val currentGroup: Long, val mode: ShelfGroupMutation)
internal data class ShelfManagementOperation(val id: String, val action: ShelfManagementAction, val ids: List<String>,
    val deleteOriginal: Boolean = false, val cron: String = "", val sourceId: String = "")
internal enum class ShelfManagementEffect { Toast, UpdateToc, ExportSources, OpenBook, PickSource, PickGroup, ManageGroups }
internal data class ShelfManagementReceipt(val id: String, val effect: ShelfManagementEffect, val resource: Int = 0,
    val args: List<Int> = emptyList(), val ids: List<String> = emptyList(), val file: String? = null)
/** Selection IDs can be large URLs; all text and receipts are owned by this durable private session. */
internal data class BookshelfManagementDraft(val revision: Long = 0, val groupId: Long = -1L, val query: String = "",
    val selected: List<String> = emptyList(), val confirmation: ShelfManagementConfirmation? = null,
    val groupRequest: ShelfGroupRequest? = null, val sourceTicket: String? = null, val sourceIds: List<String> = emptyList(),
    val operation: ShelfManagementOperation? = null, val effects: List<ShelfManagementReceipt> = emptyList())
