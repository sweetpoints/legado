package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun BookImportRoute(
    viewModel: BookImportViewModel,
    canHandle: () -> Boolean,
    onEffect: (BookImportEffect) -> Unit,
    onBusy: (Boolean) -> Unit,
    onReaderSource: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready by rememberUpdatedState(canHandle)
    val handle by rememberUpdatedState(onEffect)
    val busy by rememberUpdatedState(onBusy)
    val close by rememberUpdatedState(onClose)
    val reader by rememberUpdatedState(onReaderSource)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { if (state.groupOpen) viewModel.closeGroup() else viewModel.cancel() }
    LaunchedEffect(viewModel, lifecycle) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (ready()) {
                    busy(value.loading || value.busy || value.pendingRefresh)
                    if (value.finished && value.effects.isEmpty() && !closed) {
                        try {
                            viewModel.consumeReaderSource()?.let(reader)
                        } catch (error: Exception) {
                            if (error is kotlinx.coroutines.CancellationException) throw error
                            handle(
                                BookImportEffect(
                                    -1,
                                    BookImportAction.Toast,
                                    text = "ImportError:${error.localizedMessage}",
                                )
                            )
                        }
                        closed = true
                        close()
                    }
                    if (!value.loading && !value.busy && !value.pendingRefresh)
                        value.effects.firstOrNull()?.let { event ->
                            viewModel.consume(event.id)
                            handle(event)
                        }
                }
            }
        }
    }
    BookImportScreen(
        state,
        viewModel::search,
        viewModel::toggle,
        viewModel::selectVisible,
        viewModel::code,
        viewModel::expand,
        { menu ->
            when (menu) {
                BookImportMenu.Automatic -> viewModel.refresh(automatic = !state.automatic)
                BookImportMenu.Effective -> viewModel.effective()
                BookImportMenu.Manual -> viewModel.manual()
                BookImportMenu.ReplaceRules -> viewModel.replaceRules()
                BookImportMenu.SelectNew ->
                    viewModel.selectStatus(io.legado.app.data.repository.BookImportStatus.New)
                BookImportMenu.SelectUpdate ->
                    viewModel.selectStatus(io.legado.app.data.repository.BookImportStatus.Update)
                else -> viewModel.preferences(bookImportMenuPreferences(menu, state))
            }
        },
        viewModel::openGroup,
        viewModel::groupDraft,
        viewModel::addGroupDraft,
        viewModel::acceptGroup,
        viewModel::closeGroup,
        viewModel::confirm,
        viewModel::cancel,
        viewModel::load,
        modifier,
    )
}
