package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.BookLinkTarget

@Composable
internal fun AddBookLinkRoute(
    viewModel: AddBookLinkViewModel,
    canHandle: () -> Boolean,
    onOpenBook: (BookLinkTarget) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready by rememberUpdatedState(canHandle)
    val openBook by rememberUpdatedState(onOpenBook)
    val error by rememberUpdatedState(onError)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { viewModel.cancel() }
    LaunchedEffect(viewModel, lifecycle) {
        var closed = false
        fun closeOnce() {
            if (!closed) {
                closed = true
                close()
            }
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (ready())
                    when {
                        value.finished -> closeOnce()
                        value.target != null -> {
                            viewModel.consumeResult()
                            openBook(value.target)
                            closeOnce()
                        }
                        value.error != null -> {
                            viewModel.consumeResult()
                            error(value.error)
                            closeOnce()
                        }
                    }
            }
        }
    }
    AddBookLinkScreen(state, viewModel::cancel, modifier)
}
