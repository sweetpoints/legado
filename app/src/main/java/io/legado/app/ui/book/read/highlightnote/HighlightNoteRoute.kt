package io.legado.app.ui.book.read.highlightnote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HighlightNoteRoute(
    viewModel: HighlightNoteViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(state.finished) {
        if (state.finished) onClose()
        onPauseOrDispose {}
    }
    HighlightNoteScreen(
        state,
        viewModel::setBookText,
        viewModel::setNote,
        viewModel::submit,
        viewModel::cancel,
        viewModel::retry,
        modifier,
    )
}
