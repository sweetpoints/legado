package io.legado.app.ui.book.bookmark

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun BookmarkEditorRoute(
    model: BookmarkEditorViewModel,
    canDeliver: () -> Boolean,
    close: () -> Unit,
    cancelable: (Boolean) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    val ready by rememberUpdatedState(canDeliver)
    val dismiss by rememberUpdatedState(close)
    var closed by remember { mutableStateOf(false) }
    SideEffect { cancelable(!state.busy) }
    BackHandler { model.cancel() }
    LaunchedEffect(state.finished, lifecycle) {
        if (state.finished && !closed && lifecycle == Lifecycle.State.RESUMED && ready()) {
            closed = true
            dismiss()
        }
    }
    BookmarkEditorScreen(
        state,
        model::bookText,
        model::content,
        model::confirm,
        model::delete,
        model::cancel,
        model::load,
    )
}
