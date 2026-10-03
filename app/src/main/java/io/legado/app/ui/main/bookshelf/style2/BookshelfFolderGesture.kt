package io.legado.app.ui.main.bookshelf.style2

import kotlin.math.abs

/** Gesture state is transient and never enters SavedStateHandle or preference storage. */
internal class BookshelfFolderGesture(
    private val threshold: Float,
    private val previous: Boolean,
    private val next: Boolean,
) {
    private var captured = 0

    fun move(dx: Float, dy: Float): Boolean {
        if (captured == 0) {
            val direction = direction(dx, dy)
            if (direction == -1 && previous || direction == 1 && next) captured = direction
        }
        return captured != 0
    }

    fun finish(dx: Float, dy: Float, cancelled: Boolean): Int? {
        val result = captured.takeIf { it != 0 && !cancelled && direction(dx, dy) == it }
        captured = 0
        return result
    }

    private fun direction(dx: Float, dy: Float): Int =
        if (abs(dx) <= threshold || abs(dx) <= abs(dy)) 0 else if (dx < 0) 1 else -1
}
