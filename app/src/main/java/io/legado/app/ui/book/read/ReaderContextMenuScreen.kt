package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal data class ReaderContextAction(
    val title: String,
    val key: String,
    val danger: Boolean = false,
)

internal data class ReaderContextMenuState(
    val x: Float,
    val y: Float,
    val actions: List<ReaderContextAction>,
    val preferAbove: Boolean = false,
    val alignToStart: Boolean = true,
)

/** Context actions are Compose; only their anchor coordinates originate from the native canvas. */
@Composable
internal fun ReaderContextMenuScreen(
    state: ReaderContextMenuState?,
    dismiss: () -> Unit,
    action: (String) -> Unit,
) {
    if (state == null) return
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position =
        remember(state.x, state.y, state.preferAbove, state.alignToStart, gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset =
                    readerContextPopupOffset(
                        state,
                        anchorBounds,
                        windowSize,
                        popupContentSize,
                        layoutDirection,
                        gap,
                    )
            }
        }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = dismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(shape = MaterialTheme.shapes.extraSmall, shadowElevation = 8.dp) {
            Column(
                Modifier.width(240.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            ) {
                state.actions.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                entry.title,
                                color =
                                    if (entry.danger) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { action(entry.key) },
                        modifier = Modifier.testTag("reader-context-${entry.key}"),
                    )
                }
            }
        }
    }
}

/** Preserve above-image placement and keep actions inside the window when the page edge is near. */
internal fun readerContextPopupOffset(
    state: ReaderContextMenuState,
    anchorBounds: IntRect,
    windowSize: IntSize,
    contentSize: IntSize,
    layoutDirection: LayoutDirection,
    gap: Int,
): IntOffset {
    val anchorX = anchorBounds.left + state.x.roundToInt()
    val anchorY = anchorBounds.top + state.y.roundToInt()
    val x =
        if (state.alignToStart && layoutDirection == LayoutDirection.Rtl)
            anchorX - contentSize.width
        else anchorX
    val below = anchorY + gap
    val above = anchorY - contentSize.height - if (state.preferAbove) 0 else gap
    val y =
        when {
            state.preferAbove -> above
            below + contentSize.height <= windowSize.height -> below
            above >= 0 -> above
            else -> below
        }
    return IntOffset(
        x.coerceIn(0, (windowSize.width - contentSize.width).coerceAtLeast(0)),
        y.coerceIn(0, (windowSize.height - contentSize.height).coerceAtLeast(0)),
    )
}
