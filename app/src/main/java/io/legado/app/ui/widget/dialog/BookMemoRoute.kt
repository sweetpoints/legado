package io.legado.app.ui.widget.dialog

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.ui.components.markdown.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun BookMemoRoute(model: BookMemoViewModel, images: MarkdownImageRepository, link: (String) -> Unit,
    canClose: () -> Boolean, close: () -> Unit, cancelable: (Boolean, Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val dismiss by rememberUpdatedState(close); val ready by rememberUpdatedState(canClose)
    SideEffect { cancelable(!state.saving, !state.editing && !state.saving) }
    BackHandler(!state.saving) { model.close() }
    LaunchedEffect(state.finished, lifecycle) { if (state.finished && lifecycle == Lifecycle.State.RESUMED && ready()) dismiss() }
    val markdown = state.memo?.content.orEmpty()
    val document by produceState<List<MarkdownBlock>>(emptyList(), markdown) { value = withContext(Dispatchers.Default) { parseMarkdownDocument(markdown) } }
    BookMemoScreen(state, document, LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE, images, link,
        model::edit, model::save, model::text, model::cancelEdit, model::requestClear, model::close, model::confirm, model::cancelConfirmation, model::load)
}
