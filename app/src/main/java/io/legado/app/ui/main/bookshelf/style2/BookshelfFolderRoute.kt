package io.legado.app.ui.main.bookshelf.style2

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover
import io.legado.app.ui.components.cover.ComposeGroupCover

@Composable internal fun BookshelfFolderRoute(viewModel: BookshelfFolderViewModel,
    onEditGroup: (Long) -> Unit, onOpenBook: (String) -> Unit, onBookInfo: (String) -> Unit,
    onMenu: (Int) -> Unit, onRefresh: () -> Unit, onContinue: () -> Unit, onRecentInfo: () -> Unit,
    onBookKeys: (List<String>) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notify by rememberUpdatedState(onBookKeys)
    LifecycleResumeEffect(viewModel) { viewModel.start(); notify(viewModel.state.value.books.map { it.card.key }); onPauseOrDispose { viewModel.stop() } }
    LaunchedEffect(state.books.map { it.card.key }) { notify(state.books.map { it.card.key }) }
    BookshelfFolderScreen(state, viewModel::openGroup, onEditGroup, onOpenBook, onBookInfo, onMenu,
        { viewModel.back() }, viewModel::swipe, onRefresh, onContinue, onRecentInfo, viewModel::scrolled, viewModel::retry,
        bookCover = { book, modifier -> ComposeCover(CoverRequest(book.cover, book.card.name, book.card.author, sourceOrigin = book.sourceOrigin), modifier) },
        groupCover = { group, modifier -> ComposeGroupCover(group.cover, group.preview, modifier, group.name) })
}
