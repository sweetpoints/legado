package io.legado.app.ui.association

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun RssImportRoute(
    viewModel: RssImportViewModel,
    canHandle: () -> Boolean,
    onEffect: (RssImportEffect) -> Unit,
    onBusy: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready by rememberUpdatedState(canHandle)
    val handle by rememberUpdatedState(onEffect)
    val busy by rememberUpdatedState(onBusy)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler { if (state.groupOpen) viewModel.closeGroup() else viewModel.cancel() }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (ready()) {
                    busy(value.loading || value.busy || value.pendingRefresh)
                    if (value.finished && value.effects.isEmpty()) close()
                    if (!value.loading && !value.busy && !value.pendingRefresh)
                        value.effects.firstOrNull()?.let { event ->
                            viewModel.consume(event.id)
                            handle(event)
                        }
                }
            }
        }
    }
    RssImportScreen(
        state,
        viewModel::search,
        viewModel::toggle,
        viewModel::selectVisible,
        viewModel::code,
        viewModel::expand,
        { menu ->
            when (menu) {
                RssImportMenu.Automatic -> viewModel.refresh(automatic = !state.automatic)
                RssImportMenu.Effective -> viewModel.effective()
                RssImportMenu.Manual -> viewModel.manual()
                RssImportMenu.ReplaceRules -> viewModel.replaceRules()
                else -> viewModel.preferences(rssImportMenuPreferences(menu, state))
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
