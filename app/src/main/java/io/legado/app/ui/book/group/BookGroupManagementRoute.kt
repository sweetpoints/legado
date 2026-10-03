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
fun BookGroupManagementRoute(
    model: BookGroupManagementViewModel,
    onAdd: () -> Unit,
    onEdit: (BookGroupEditorSnapshot) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val add by rememberUpdatedState(onAdd)
    val close by rememberUpdatedState(onClose)
    BackHandler { model.close() }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.finished && !closed) {
                    closed = true
                    close()
                } else if (!it.finished && it.pendingAdd && model.consumeAdd()) add()
            }
        }
    }
    BookGroupManagementScreen(
        state,
        BookGroupManagementActions(
            model::close,
            model::requestAdd,
            onEdit,
            model::setShown,
            model::move,
            model::finishReorder,
            model::cancelReorder,
            model::retry,
        ),
        modifier,
    )
}
