package io.legado.app.ui.main.bookshelf.style1

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageScreen
import io.legado.app.ui.main.bookshelf.style1.books.BookshelfPageViewModel

@Composable internal fun BookshelfHomeRoute(viewModel: BookshelfHomeViewModel,
    onReselect: (Long) -> Unit, onGroupInfo: (Long) -> Unit, onMenu: (Int) -> Unit,
    onContinue: () -> Unit, onRecentInfo: () -> Unit,
    page: @Composable (BookshelfHomeGroup, Int, Boolean, Modifier) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) { viewModel.start(); onPauseOrDispose { viewModel.stop() } }
    BookshelfHomeScreen(state, viewModel::select, onReselect, onGroupInfo, onMenu, onContinue, onRecentInfo, viewModel::retry, page)
}

/** Offscreen pager pages retain state but never run their collectors or age ticker. */
@Composable internal fun BookshelfGroupPageRoute(model: BookshelfPageViewModel, active: Boolean,
    onRefresh: () -> Unit, onOpen: (String) -> Unit, onInfo: (String) -> Unit,
    onBookKeys: (List<String>) -> Unit, modifier: Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val notify by rememberUpdatedState(onBookKeys)
    LifecycleResumeEffect(model, active) {
        if (active) { model.start(); notify(model.state.value.entries.map { it.key }) } else model.stop()
        onPauseOrDispose { model.stop() }
    }
    LaunchedEffect(active, state.entries.map { it.key }) { if (active) notify(state.entries.map { it.key }) }
    BookshelfPageScreen(state, onRefresh, onOpen, onInfo, model::scrolled, model::retry, modifier) { entry, coverModifier ->
        ComposeCover(CoverRequest(entry.cover, entry.name, entry.author, sourceOrigin = entry.sourceOrigin), coverModifier)
    }
}
