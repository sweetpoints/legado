package io.legado.app.ui.book.group

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun BookGroupEditorRoute(
    model: BookGroupEditorViewModel,
    onSelectImage: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    BackHandler { model.close() }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.finished && !closed) {
                    closed = true
                    close()
                }
            }
        }
    }
    fun launchImage(select: () -> Boolean) {
        if (select())
            try {
                onSelectImage()
            } catch (_: Exception) {
                model.coverResult(null)
            }
    }
    BookGroupEditorScreen(
        state,
        model::name,
        model::sort,
        model::refresh,
        model::onlyRead,
        { launchImage(model::requestCover) },
        { launchImage(model::selectCover) },
        model::removeCover,
        { model.coverMenu(false) },
        model::save,
        model::close,
        model::requestDelete,
        model::confirmDelete,
        model::load,
        modifier,
    )
}
