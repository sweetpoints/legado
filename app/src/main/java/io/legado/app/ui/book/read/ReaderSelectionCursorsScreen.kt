package io.legado.app.ui.book.read

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable
internal fun ReaderSelectionCursorsScreen(
    state: ReaderSelectionState,
    color: Color,
    begin: () -> Unit,
    move: (SelectionHandle, Offset) -> Unit,
    end: () -> Unit,
) {
    val cursorWidth = with(LocalDensity.current) { 24.dp.toPx() }
    Box(Modifier.fillMaxSize()) {
        if (state.showStart)
            SelectionCursor(
                SelectionHandle.Start,
                state.startX - cursorWidth,
                state.startY,
                color,
                begin,
                move,
                end,
            )
        if (state.showEnd)
            SelectionCursor(
                SelectionHandle.End,
                state.endX,
                state.endY,
                color,
                begin,
                move,
                end,
            )
    }
}

@Composable
private fun SelectionCursor(
    handle: SelectionHandle,
    x: Float,
    y: Float,
    color: Color,
    begin: () -> Unit,
    move: (SelectionHandle, Offset) -> Unit,
    end: () -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val currentBegin by rememberUpdatedState(begin)
    val currentMove by rememberUpdatedState(move)
    val currentEnd by rememberUpdatedState(end)
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(48.dp)
            .testTag(
                if (handle == SelectionHandle.Start) "reader-selection-start"
                else "reader-selection-end"
            )
            .onGloballyPositioned { coordinates = it }
            .pointerInput(handle) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentBegin()
                    try {
                        var pressed = true
                        while (pressed) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            pressed = change.pressed
                            if (pressed && change.position != change.previousPosition) {
                                // The native engine can reposition a cursor during the gesture.
                                // Convert the
                                // latest local position to screen coordinates, never accumulate
                                // local deltas.
                                coordinates
                                    ?.takeIf { it.isAttached }
                                    ?.let { layout ->
                                        currentMove(handle, layout.localToScreen(change.position))
                                    }
                            }
                            change.consume()
                        }
                    } finally {
                        // Match both native ACTION_UP and ACTION_CANCEL, including disposal during
                        // drag.
                        currentEnd()
                    }
                }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        Icon(
            painterResource(
                if (handle == SelectionHandle.Start) R.drawable.ic_cursor_left
                else R.drawable.ic_cursor_right
            ),
            stringResource(
                if (handle == SelectionHandle.Start) R.string.select_start else R.string.select_end
            ),
            Modifier.size(24.dp),
            tint = color,
        )
    }
}
