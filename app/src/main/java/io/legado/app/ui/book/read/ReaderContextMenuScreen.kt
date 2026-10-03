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
)

/** Context actions are Compose; only their anchor coordinates originate from the native canvas. */
@Composable
internal fun ReaderContextMenuScreen(
    state: ReaderContextMenuState?,
    dismiss: () -> Unit,
    action: (String) -> Unit,
) {
    if (state == null) return
    val position =
        remember(state.x, state.y) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset =
                    IntOffset(
                        state.x
                            .roundToInt()
                            .coerceIn(
                                0,
                                (windowSize.width - popupContentSize.width).coerceAtLeast(0),
                            ),
                        state.y
                            .roundToInt()
                            .coerceIn(
                                0,
                                (windowSize.height - popupContentSize.height).coerceAtLeast(0),
                            ),
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
