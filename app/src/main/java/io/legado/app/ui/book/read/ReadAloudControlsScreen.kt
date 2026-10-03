package io.legado.app.ui.book.read

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

internal data class ReadAloudControlPresentation(
    val showPause: Boolean = true,
    val paused: Boolean = false,
    val drag: Boolean = false,
    val width: Float = 288f,
    val background: Color = Color.White,
    val foreground: Color = Color.Black,
    val borderAlpha: Float = .25f,
)

/** Only the canvas reader remains native; floating controls have Compose gestures and semantics. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReadAloudControlsScreen(
    state: ReadAloudControlPresentation,
    pause: () -> Unit,
    stop: () -> Unit,
    back: () -> Unit,
    readHere: () -> Unit,
    dragStart: () -> Unit,
    drag: (Float, Float) -> Unit,
    dragEnd: () -> Unit,
    dragCancel: () -> Unit,
) {
    val start = rememberUpdatedState(dragStart)
    val move = rememberUpdatedState(drag)
    val end = rememberUpdatedState(dragEnd)
    val cancel = rememberUpdatedState(dragCancel)
    val scale = state.width / 288f
    val compact = scale < .65f
    val view = LocalView.current
    val gesture =
        if (state.drag)
            Modifier.pointerInput(Unit) {
                val location = IntArray(2)
                var previousScreenX = 0f
                var previousScreenY = 0f
                detectDragGestures(
                    onDragStart = { position ->
                        view.getLocationOnScreen(location)
                        previousScreenX = location[0] + position.x
                        previousScreenY = location[1] + position.y
                        start.value()
                    },
                    onDragEnd = { end.value() },
                    onDragCancel = { cancel.value() },
                ) { change, _ ->
                    // The host moves while dragging. Screen coordinates avoid counting that motion
                    // again when Compose receives the next pointer position in its new local space.
                    view.getLocationOnScreen(location)
                    val screenX = location[0] + change.position.x
                    val screenY = location[1] + change.position.y
                    change.consume()
                    move.value(screenX - previousScreenX, screenY - previousScreenY)
                    previousScreenX = screenX
                    previousScreenY = screenY
                }
            }
        else Modifier
    Surface(
        modifier = Modifier.fillMaxSize().then(gesture).testTag("reader-aloud-controls"),
        shape = RoundedCornerShape(50),
        color = state.background,
        contentColor = state.foreground,
        border = BorderStroke(1.dp, state.foreground.copy(alpha = state.borderAlpha)),
    ) {
        if (state.showPause)
            Box(
                Modifier.fillMaxSize()
                    .combinedClickable(onClick = pause, onLongClick = stop)
                    .testTag("reader-aloud-pause"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(
                        if (state.paused) R.drawable.ic_play_24dp else R.drawable.ic_pause_24dp
                    ),
                    stringResource(if (state.paused) R.string.resume else R.string.pause),
                    Modifier.size((24 * scale).dp),
                )
            }
        else
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                ReadAloudPositionAction(
                    if (compact) R.string.read_aloud_back_short
                    else R.string.back_to_speaking_position,
                    R.drawable.ic_read_aloud,
                    compact,
                    scale,
                    back,
                    Modifier.weight(1f).testTag("reader-aloud-back"),
                )
                Surface(
                    Modifier.width(1.dp).height((20 * scale).dp),
                    color = state.foreground.copy(alpha = .3f),
                ) {}
                ReadAloudPositionAction(
                    if (compact) R.string.read_aloud_here_short else R.string.read_aloud_from_here,
                    R.drawable.ic_play_24dp,
                    compact,
                    scale,
                    readHere,
                    Modifier.weight(1f).testTag("reader-aloud-here"),
                )
            }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReadAloudPositionAction(
    label: Int,
    icon: Int,
    compact: Boolean,
    scale: Float,
    click: () -> Unit,
    modifier: Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    var textSize by
        remember(label, scale, fontScale) {
            mutableFloatStateOf((if (compact) 22 else 14) * scale)
        }
    Row(
        modifier
            .fillMaxHeight()
            .combinedClickable(onClick = click)
            .padding(horizontal = ((if (compact) 2 else 8) * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            Icon(painterResource(icon), null, Modifier.size((20 * scale).dp))
            Spacer(Modifier.width((4 * scale).dp))
        }
        Text(
            stringResource(label),
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = textSize.sp,
            lineHeight = (textSize * 1.15f).sp,
            maxLines = if (compact) 1 else 2,
            onTextLayout = { layout ->
                // Keep full action labels readable inside the user's chosen width,
                // including large system font settings and translated strings.
                if (layout.hasVisualOverflow && textSize > 1f) {
                    textSize = (textSize * .9f).coerceAtLeast(1f)
                }
            },
        )
    }
}
