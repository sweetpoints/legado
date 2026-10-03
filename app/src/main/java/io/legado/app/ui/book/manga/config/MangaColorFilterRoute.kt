package io.legado.app.ui.book.manga.config

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
internal fun MangaColorFilterRoute(
    viewModel: MangaColorFilterViewModel,
    onPreview: (MangaColorFilterConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by rememberUpdatedState(onPreview)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var deliveredRevision = -1
            viewModel.state.collect {
                if (
                    !it.finished &&
                        it.previewRevision > 0 &&
                        it.previewRevision != deliveredRevision
                ) {
                    deliveredRevision = it.previewRevision
                    preview(it.values.toReaderConfig())
                }
            }
        }
    }
    MangaColorFilterScreen(state, viewModel::change, viewModel::load, modifier)
}
