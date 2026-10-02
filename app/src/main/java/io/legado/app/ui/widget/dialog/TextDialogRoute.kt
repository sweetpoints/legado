package io.legado.app.ui.widget.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.data.repository.TextDialogRequest
import io.legado.app.ui.components.markdown.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun TextDialogRoute(model: TextDialogViewModel, images: MarkdownImageRepository, ready: () -> Boolean,
    link: (String) -> Unit, image: (String) -> Unit, edit: (TextDialogRequest) -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val canDeliver by rememberUpdatedState(ready); val openEditor by rememberUpdatedState(edit); val dismiss by rememberUpdatedState(close)
    val openLink by rememberUpdatedState(link); val openImage by rememberUpdatedState(image)
    SideEffect { cancelable(state.canCancel) }
    LifecycleResumeEffect(model) { model.tick(); onPauseOrDispose {} }
    BackHandler { if (state.tocVisible) model.toc(false) else if (state.canCancel) model.close() }
    LaunchedEffect(state.finished, state.editPending, lifecycle, state.request) {
        if (lifecycle != Lifecycle.State.RESUMED || !canDeliver()) return@LaunchedEffect
        if (state.finished) dismiss() else model.consumeEdit()?.let(openEditor)
    }
    val simpleSource = state.request?.takeIf { it.mode == "MD" && !state.help && !it.content.contains(Regex("<(?:[a-zA-Z]|/)")) && !it.content.contains("![") }?.content
    val simple by produceState<List<MarkdownBlock>?>(null, simpleSource) {
        value = null; simpleSource?.let { value = withContext(Dispatchers.Default) { parseMarkdownDocument(it) } }
    }
    TextDialogScreen(state, images, simple, { if (lifecycle == Lifecycle.State.RESUMED && canDeliver()) openLink(it) },
        { if (lifecycle == Lifecycle.State.RESUMED && canDeliver()) openImage(it) }, model::close, model::edit, model::toggleSearch,
        model::query, model::moveMatch, model::toc, model::section, model::load, model::scrolled, model::position)
}
