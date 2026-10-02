package io.legado.app.ui.rss.source.edit

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.RssSourceEditorAssistRepository
import io.legado.app.data.repository.RssSourceEditorAssistPreferences
import io.legado.app.data.repository.RssSourceEditorShareRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

@Composable fun RssSourceEditorRoute(model: RssSourceEditorViewModel,
    onEvent: (RssSourceEditorEffect, String?) -> Unit, onClose: (Boolean) -> Unit, onError: (String) -> Unit,
    canHandle: () -> Boolean = { true }, onKeyboardConfig: () -> Unit = {},
    assistRepository: RssSourceEditorAssistRepository = remember { RssSourceEditorAssistRepository() },
    shareRepository: RssSourceEditorShareRepository = remember { RssSourceEditorShareRepository(splitties.init.appCtx) }) {
    val state by model.state.collectAsStateWithLifecycle(); val owner = LocalLifecycleOwner.current
    val keys = remember(assistRepository) { assistRepository.keys().catch { emit(emptyList()) } }
    val assists by keys.collectAsStateWithLifecycle(emptyList())
    val preferences = remember(assistRepository) { assistRepository.preferences() }
    val settings by preferences.collectAsStateWithLifecycle(RssSourceEditorAssistPreferences(assistRepository.rows, assistRepository.maxLines))
    val deliver by rememberUpdatedState(onEvent); val close by rememberUpdatedState(onClose)
    val ready by rememberUpdatedState(canHandle); val error by rememberUpdatedState(onError)
    var closed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (!ready() || value.loading || value.busy) return@collect
                if (value.finished && !closed) { closed = true; close(value.savedResult); return@collect }
                for (effect in value.effects) {
                    if (!ready()) break
                    try {
                        val payload = when (effect.kind) {
                            RssSourceEditorEffectKind.Clipboard, RssSourceEditorEffectKind.ShareText -> model.copyText()
                            RssSourceEditorEffectKind.ShareQr -> shareRepository.qr(model.copyText())
                            RssSourceEditorEffectKind.SavedVariable -> model.variable()
                            else -> null
                        }
                        currentCoroutineContext().ensureActive()
                        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !ready()) break
                        model.consume(effect)
                        if (effect.kind == RssSourceEditorEffectKind.Close || effect.kind == RssSourceEditorEffectKind.SavedClose) {
                            if (!closed) { closed = true; close(model.state.value.savedResult) }
                        } else deliver(effect, payload)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        currentCoroutineContext().ensureActive(); model.consume(effect)
                        if (effect.kind == RssSourceEditorEffectKind.Editor) model.editorReturned(false, null, null, 0)
                        error(failure.localizedMessage ?: "Error")
                    }
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { runCatching { model.flush() } } }
        }
    }
    val config by rememberUpdatedState(onKeyboardConfig)
    val actions = remember(model, owner) { RssSourceEditorActions(field = model::field, focus = model::focus, tab = model::tab,
        options = model::options, expanded = model::expanded, autoComplete = model::autoComplete, save = model::save,
        exit = model::requestExit, keep = model::keepEditing, discard = model::discard, editor = model::openEditor,
        action = model::action, cookie = model::clearCookie, paste = model::requestPaste, retry = model::retry,
        retryEditor = model::retryEditorResult, discardEditor = model::discardEditorResult, insert = model::insert, undo = model::undo, redo = model::redo,
        keyboardConfig = { if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()) config() }) }
    BackHandler { if (state.exit) model.keepEditing() else model.requestExit() }
    RssSourceEditorScreen(state, actions, assists, settings.rows, settings.maxLines)
}
