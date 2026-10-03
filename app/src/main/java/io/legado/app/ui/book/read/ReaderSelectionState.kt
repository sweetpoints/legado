package io.legado.app.ui.book.read

/** Pixel positions belong to the native page viewport; selection is deliberately not persisted. */
internal data class ReaderSelectionState(
    val startX: Float = 0f,
    val startY: Float = 0f,
    val startTop: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val showStart: Boolean = false,
    val showEnd: Boolean = false,
)

internal enum class SelectionHandle {
    Start,
    End,
}

internal enum class SelectionEndpoint {
    Start,
    End,
}

internal data class SelectionDragCommand(
    val endpoint: SelectionEndpoint,
    val screenX: Float,
    val screenY: Float,
)

/**
 * Preserve the native cursor's reverse-selection offsets, independently of the Compose touch
 * target.
 */
internal fun selectionDragCommand(
    handle: SelectionHandle,
    reverseStart: Boolean,
    reverseEnd: Boolean,
    rawX: Float,
    rawY: Float,
    cursorWidth: Float,
    cursorHeight: Float,
): SelectionDragCommand {
    val movesStart =
        when (handle) {
            SelectionHandle.Start -> !reverseStart
            SelectionHandle.End -> reverseEnd
        }
    return SelectionDragCommand(
        if (movesStart) SelectionEndpoint.Start else SelectionEndpoint.End,
        rawX + if (movesStart) cursorWidth else -cursorWidth,
        rawY - cursorHeight,
    )
}
