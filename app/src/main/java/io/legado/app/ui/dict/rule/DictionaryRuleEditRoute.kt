package io.legado.app.ui.dict.rule

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun DictionaryRuleEditRoute(
    model: DictionaryRuleEditViewModel,
    onFullEdit: (DictionaryFullEditRequest) -> Unit,
    onCopy: (String) -> Unit,
    onPaste: () -> String?,
    onNoFocus: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    BackHandler { model.requestClose() }
    LaunchedEffect(model, lifecycle) {
        var closed = false
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect {
                if (it.finished && !closed) {
                    closed = true
                    close()
                }
            }
        }
    }
    DictionaryRuleEditScreen(
        state,
        model::setInput,
        model::focus,
        { model.fullEditRequest()?.let(onFullEdit) ?: onNoFocus() },
        model::save,
        { onCopy(model.copyJson()) },
        { model.paste(onPaste()) },
        model::requestClose,
        model::keepEditing,
        model::discard,
        model::load,
        modifier,
    )
}
