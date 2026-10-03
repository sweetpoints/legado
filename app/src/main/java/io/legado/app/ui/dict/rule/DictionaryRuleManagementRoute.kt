package io.legado.app.ui.dict.rule

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun DictionaryRuleManagementRoute(
    model: DictionaryRuleManagementViewModel,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onImportFile: () -> Unit,
    onImportQr: () -> Unit,
    onHelp: () -> Unit,
    onEffect: (DictionaryManagementEffect) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onEffect)
    LaunchedEffect(model, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { if (it.effect != null) model.consumeEffect()?.let(deliver) }
        }
    }
    val actions =
        DictionaryManagementActions(
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
        )
    DictionaryRuleManagementScreen(state, actions)
}
