package io.legado.app.ui.book.group

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.BookGroupEditorSnapshot

@Composable
fun BookGroupSelectionRoute(
    model: BookGroupSelectionViewModel,
    onAdd: () -> Unit,
    onEdit: (BookGroupEditorSnapshot) -> Unit,
    onResult: (BookGroupSelectionResult) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    val close by rememberUpdatedState(onClose)
    BackHandler { model.close() }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.finished && !closed) {
                    model.consumeResult()?.let(result)
                    closed = true
                    close()
                }
            }
        }
    }
    BookGroupSelectionScreen(
        state,
        BookGroupSelectionActions(
            model::close,
            onAdd,
            onEdit,
            model::setChecked,
            model::move,
            model::finishReorder,
            model::cancelReorder,
            model::retry,
            model::confirm,
        ),
        modifier,
    )
}
