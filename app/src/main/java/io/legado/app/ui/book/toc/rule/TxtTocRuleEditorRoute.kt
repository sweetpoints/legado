package io.legado.app.ui.book.toc.rule

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.TxtTocRuleSnapshot

@Composable
fun TxtTocRuleEditorRoute(model: TxtTocRuleEditorViewModel, onCode: (TxtTocEditorCodeRequest) -> Unit,
    onCopy: (String) -> Unit, onPaste: () -> String?, onNoFocus: () -> Unit,
    onSaved: (TxtTocRuleSnapshot) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val saved by rememberUpdatedState(onSaved)
    BackHandler { model.requestClose() }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.finished && !closed) {
                    model.consumeSavedRule()?.let(saved)
                    closed = true; close()
                }
            }
        }
    }
    TxtTocRuleEditorScreen(state, model::setInput, model::focus,
        { model.codeRequest()?.let(onCode) ?: onNoFocus() }, model::save,
        { onCopy(model.copyJson()) }, { model.paste(onPaste()) }, model::requestClose,
        model::keepEditing, model::discard, model::load, modifier)
}
