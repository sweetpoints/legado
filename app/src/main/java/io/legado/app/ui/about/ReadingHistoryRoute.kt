package io.legado.app.ui.about

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.ReadingHistoryCoverRepository
import io.legado.app.data.repository.ReadingHistoryDestination
import kotlinx.coroutines.*

@Composable fun ReadingHistoryRoute(model: ReadingHistoryViewModel, covers: ReadingHistoryCoverRepository,
    onBack: () -> Unit, onOpen: (ReadingHistoryDestination) -> Unit, onError: (String) -> Unit,
    canHandle: () -> Boolean = { true }) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val open by rememberUpdatedState(onOpen); val close by rememberUpdatedState(onBack)
    val error by rememberUpdatedState(onError); val ready by rememberUpdatedState(canHandle)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun back() {
        if (closing) return
        closing = true
        scope.launch { try { model.abandon() } catch (failure: Exception) { error(failure.localizedMessage ?: "ERROR") } finally { close() } }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (model.state.value.ready) model.resume()
            model.state.collect { current -> current.navigation?.let { navigation ->
                try {
                    val destination = model.consumeNavigation(navigation.id) { ready() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    currentCoroutineContext().ensureActive()
                    if (destination != null && ready() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) open(destination)
                } catch (failure: Exception) { currentCoroutineContext().ensureActive(); error(failure.localizedMessage ?: "ERROR") }
            } }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) { runCatching { model.flush() } } }
        }
    }
    BackHandler { back() }
    val actions = remember(model, owner) { ReadingHistoryActions(model::query, model::preference, model::open, model::delete, model::clear,
        model::confirmDelete, model::dismissConfirmation, model::chooseAuthor, model::removeAuthor, model::retry, model::dismissError, ::back) }
    ReadingHistoryScreen(state, actions, covers)
}
