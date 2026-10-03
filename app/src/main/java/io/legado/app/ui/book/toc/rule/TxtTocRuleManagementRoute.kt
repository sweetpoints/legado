package io.legado.app.ui.book.toc.rule

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun TxtTocRuleManagementRoute(
    model: TxtTocRuleManagementViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onImportFile: () -> Unit,
    onImportQr: () -> Unit,
    onHelp: () -> Unit,
    onEffect: (TxtTocManagementEffect) -> Unit,
    picker: Boolean = false,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onEffect)
    val close by rememberUpdatedState(onBack)
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.effect != null) model.consumeEffect()?.let(deliver)
                if (picker && it.pickerFinished && !closed) {
                    closed = true
                    close()
                }
            }
        }
    }
    val actions =
        TxtTocManagementActions(
            onBack,
            onAdd,
            onEdit,
            onImportFile,
            onImportQr,
            onHelp,
            model::toggle,
            model::selectAll,
            model::invert,
            model::setEnabled,
            model::enableSelection,
            model::requestDelete,
            model::confirmDelete,
            model::deleteSelection,
            model::share,
            model::export,
            model::importDefault,
            model::showOnline,
            model::inputOnline,
            model::deleteHistory,
            model::confirmOnline,
            model::closeExport,
            model::copyExport,
            model::createPassphrase,
            model::closePassphrase,
            model::copyPassphrase,
            model::beginSlide,
            model::slideTo,
            model::endSlide,
            model::move,
            model::finishReorder,
            model::retry,
            model::cancelSlide,
            model::cancelReorder,
            model::cancelDelete,
            model::inputQuery,
            model::toEdge,
            model::choose,
            model::confirmChoice,
        )
    TxtTocRuleManagementScreen(state, actions, picker = picker)
}
