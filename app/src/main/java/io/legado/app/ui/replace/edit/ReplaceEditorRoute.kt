package io.legado.app.ui.replace.edit

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.repository.ReplaceEditorAssistRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
fun ReplaceEditorRoute(
    model: ReplaceEditorViewModel,
    assist: ReplaceEditorAssistRepository,
    nativeReady: () -> Boolean,
    launchEditor: (ReplaceEditorLaunch, Int) -> Unit,
    close: (Boolean) -> Unit,
    copied: (String) -> Unit,
    clipboard: () -> String?,
    help: () -> Unit,
    config: () -> Unit,
    notice: (String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val keys by remember(assist) { assist.keys() }.collectAsStateWithLifecycle(emptyList())
    val rows by remember(assist) { assist.rows() }.collectAsStateWithLifecycle(1)
    val scope = rememberCoroutineScope()
    val currentReady by rememberUpdatedState(nativeReady)
    val currentLaunch by rememberUpdatedState(launchEditor)
    val currentClose by rememberUpdatedState(close)
    val currentNotice by rememberUpdatedState(notice)
    val focusNotice = stringResource(R.string.please_focus_cursor_on_textbox)
    val truncationNotice =
        stringResource(R.string.replace_preview_truncated, ReplacePreview.MAX_SAMPLE_LENGTH)
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(
        state.finished,
        state.editor,
        state.editorLaunch,
        state.error,
        state.truncated,
        state.focusRequired,
        lifecycle,
    ) {
        if (!lifecycle.isAtLeast(Lifecycle.State.RESUMED) || !currentReady()) return@LaunchedEffect
        if (state.finished) {
            if (!closed) {
                closed = true
                currentClose(state.saved)
            }
        } else {
            if (state.error != null || state.truncated || state.focusRequired) {
                model.clearNotice()
                currentNotice(
                    state.error ?: if (state.focusRequired) focusNotice else truncationNotice
                )
            }
            state.editor
                ?.takeIf { state.editorLaunch }
                ?.let { launch ->
                    model.editorLaunched(launch.nonce)
                    try {
                        currentLaunch(launch, state.draft[launch.field].start)
                    } catch (error: Exception) {
                        model.editorResult(launch.nonce, null, null, null, false)
                        currentNotice(error.localizedMessage ?: "Error")
                    }
                }
        }
    }
    fun ready() = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && nativeReady()
    ReplaceEditorScreen(
        state,
        ReplaceEditorActions(
            field = model::field,
            focus = model::focus,
            flags = { regex, title, source, content -> model.flags(regex, title, source, content) },
            save = model::save,
            close = model::close,
            keep = model::keepEditing,
            discard = model::discard,
            editor = model::openEditor,
            copy = {
                scope.launch {
                    val json = model.copyJson()
                    currentCoroutineContext().ensureActive()
                    if (json != null && ready()) copied(json)
                }
            },
            paste = { if (ready()) model.paste(clipboard().orEmpty()) },
            help = { if (ready()) help() },
            config = { if (ready()) config() },
            insert = model::insert,
            undo = model::undo,
            redo = model::redo,
            retry = model::load,
            scroll = model::scroll,
            retryEditor = model::retryEditor,
            discardEditor = model::discardEditor,
        ),
        keys = keys,
        keyboardRows = rows,
        keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0,
    )
}
