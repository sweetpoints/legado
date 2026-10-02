package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.preferences.BookshelfSettingsEffects

@Composable internal fun BookshelfSettingsRoute(viewModel: BookshelfSettingsViewModel,
    onCommit: (BookshelfSettingsEffects) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BookshelfSettingsScreen(state, viewModel::edit, { viewModel.confirm()?.let(onCommit); onClose() },
        { viewModel.cancel(); onClose() }, modifier)
}
