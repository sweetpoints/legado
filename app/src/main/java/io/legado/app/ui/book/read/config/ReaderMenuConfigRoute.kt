package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun ReaderMenuConfigRoute(
    viewModel: ReaderMenuConfigViewModel,
    onClose: () -> Unit,
    onRefreshMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refresh by rememberUpdatedState(onRefreshMenu)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect {
                if (it.refreshRequest > 0) {
                    refresh()
                    viewModel.refreshed(it.refreshRequest)
                }
            }
        }
    }
    ReaderMenuConfigScreen(state, viewModel::edit, onClose, modifier)
}
