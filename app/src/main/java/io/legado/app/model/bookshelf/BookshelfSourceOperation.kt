package io.legado.app.model.bookshelf

internal sealed interface ShelfSourceEvent {
    data class Progress(val position: Int, val total: Int) : ShelfSourceEvent
    data class Completed(val changed: Int, val skipped: Int, val failed: Int) : ShelfSourceEvent
}
internal enum class ShelfSourceStage { Search, Information, Chapters }
