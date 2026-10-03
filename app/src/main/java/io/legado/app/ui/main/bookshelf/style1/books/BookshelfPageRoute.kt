package io.legado.app.ui.main.bookshelf.style1.books

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover

@Composable
internal fun BookshelfPageRoute(
    viewModel: BookshelfPageViewModel,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onInfo: (String) -> Unit,
    onBookKeys: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifyBooks by rememberUpdatedState(onBookKeys)
    val keys = state.entries.map { it.key }
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        notifyBooks(viewModel.state.value.entries.map { it.key })
        onPauseOrDispose { viewModel.stop() }
    }
    LaunchedEffect(keys) { notifyBooks(keys) }
    BookshelfPageScreen(
        state,
        onRefresh,
        onOpen,
        onInfo,
        viewModel::scrolled,
        viewModel::retry,
        modifier,
    ) { entry, coverModifier ->
        ComposeCover(
            CoverRequest(entry.cover, entry.name, entry.author, sourceOrigin = entry.sourceOrigin),
            coverModifier,
        )
    }
}
