package io.legado.app.ui.book.read

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import io.legado.app.R
import io.legado.app.help.HighlightStyle.Underline

@Composable
internal fun UnderlineEditRoute(initial: Underline, onConfirm: (Underline) -> Unit,
    onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var width by rememberSaveable { mutableIntStateOf(halfStepProgress(initial.width, 0f, 10f)) }
    var distance by rememberSaveable { mutableIntStateOf(halfStepProgress(initial.distance, 0f, 30f)) }
    var completed by rememberSaveable { mutableStateOf(false) }
    HighlightParameterScreen(R.string.highlight_underline, listOf(
        HighlightParameter("underline-width", R.string.highlight_underline_width, width, 20),
        HighlightParameter("underline-distance", R.string.highlight_underline_distance, distance, 60)),
        { index, value -> if (!completed) when (index) {
            0 -> width = value.coerceIn(0, 20)
            1 -> distance = value.coerceIn(0, 60)
        } },
        { if (!completed) { completed = true; onCancel() } },
        { if (!completed) { completed = true; onConfirm(initial.copy(width = width / 2f,
            distance = distance / 2f).normalized()) } }, modifier)
}
