package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable internal fun CodePositionBar(progress: Float, enabled: Boolean, onProgress: (Float) -> Unit) {
    val changed by rememberUpdatedState(onProgress)
    val label = stringResource(R.string.content_edit_position)
    val track = MaterialTheme.colorScheme.outlineVariant
    val thumb = MaterialTheme.colorScheme.primary
    Canvas(Modifier.width(48.dp).fillMaxHeight().testTag("code-position").semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
        if (!enabled) disabled() else setProgress { changed(it.coerceIn(0f, 1f)); true }
    }.pointerInput(enabled) {
        if (enabled) awaitEachGesture {
            val first = awaitFirstDown(); first.consume()
            changed((first.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
            do {
                val event = awaitPointerEvent()
                event.changes.firstOrNull { it.id == first.id }?.let { change ->
                    if (!change.isConsumed) { changed((change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)); change.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }) {
        val x = size.width / 2
        drawLine(track, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
        drawCircle(if (enabled) thumb else track, 7.dp.toPx(), Offset(x, size.height * progress))
    }
}
