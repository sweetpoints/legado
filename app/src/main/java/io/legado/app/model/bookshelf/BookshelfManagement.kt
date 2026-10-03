package io.legado.app.model.bookshelf

/** UI projections never expose mutable Room records or reading configuration. */
internal data class ManagedShelfBook(
    val id: String,
    val name: String,
    val author: String,
    val originName: String,
    val local: Boolean,
    val group: Long,
    val groupNames: String,
    val order: Int,
    val canUpdate: Boolean,
)

internal data class ManagedShelfGroup(val id: Long, val name: String, val order: Int)

internal data class ManagedShelfSnapshot(
    val books: List<ManagedShelfBook>,
    val groups: List<ManagedShelfGroup>,
    val groupId: Long,
    val groupName: String?,
    val sort: Int,
    val openTitle: Boolean,
)

internal enum class ShelfGroupMutation {
    Replace,
    Add,
    Remove,
}

internal data class ShelfOrderAssignment(val id: String, val order: Int)
