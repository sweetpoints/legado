package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import io.legado.app.R
import io.legado.app.help.HighlightStyle.Shadow

@Composable
internal fun ShadowEditRoute(
    initial: Shadow,
    onConfirm: (Shadow) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var radius by rememberSaveable { mutableIntStateOf(halfStepProgress(initial.radius, 0f, 10f)) }
    var dx by rememberSaveable { mutableIntStateOf(halfStepProgress(initial.dx, -10f, 10f)) }
    var dy by rememberSaveable { mutableIntStateOf(halfStepProgress(initial.dy, -10f, 10f)) }
    var completed by rememberSaveable { mutableStateOf(false) }
    HighlightParameterScreen(
        R.string.highlight_shadow,
        listOf(
            HighlightParameter("shadow-radius", R.string.highlight_shadow_radius, radius, 20),
            HighlightParameter("shadow-dx", R.string.highlight_shadow_offset_x, dx, 40, 20, true),
            HighlightParameter("shadow-dy", R.string.highlight_shadow_offset_y, dy, 40, 20, true),
        ),
        { index, value ->
            if (!completed)
                when (index) {
                    0 -> radius = value.coerceIn(0, 20)
                    1 -> dx = value.coerceIn(0, 40)
                    2 -> dy = value.coerceIn(0, 40)
                }
        },
        {
            if (!completed) {
                completed = true
                onCancel()
            }
        },
        {
            if (!completed) {
                completed = true
                onConfirm(
                    initial.copy(
                        radius = radius / 2f,
                        dx = dx / 2f - 10f,
                        dy = dy / 2f - 10f,
                    )
                )
            }
        },
        modifier,
    )
}
