package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun BookshelfInputRoute(
    viewModel: BookshelfInputViewModel,
    onConfirm: (Int, BookshelfInputResult) -> Unit,
    onSelectFile: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BookshelfInputScreen(
        state,
        viewModel.kind,
        viewModel.summary,
        viewModel::edit,
        {
            viewModel.confirm()?.let { onConfirm(viewModel.kind, it) }
            onClose()
        },
        {
            viewModel.cancel()
            onClose()
        },
        {
            viewModel.selectFile()?.let(onSelectFile)
            onClose()
        },
    )
}
