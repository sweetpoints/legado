package io.legado.app.model.bookshelf

internal enum class ShelfCoverAction {
    PersistNetwork,
    RestoreNetwork,
    RestoreSource,
}

internal data class ShelfCoverSummary(val saved: Int, val skipped: Int, val failed: Int)

internal sealed interface ShelfCoverEvent {
    data class Progress(val position: Int, val total: Int) : ShelfCoverEvent

    data class Completed(val summary: ShelfCoverSummary) : ShelfCoverEvent
}
