package io.legado.app.ui.rss.article

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.RssReadRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.legado.app.utils.isAbsUrl

@Composable internal fun RssReadRecordRoute(model: RssReadRecordViewModel, canDeliver: () -> Boolean,
    read: (RssReadRecord) -> Unit, browser: (String) -> Unit, close: () -> Unit, cancelable: (Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val ready by rememberUpdatedState(canDeliver); val open by rememberUpdatedState(read)
    val url by rememberUpdatedState(browser); val dismiss by rememberUpdatedState(close)
    SideEffect { cancelable(!(state.busy && state.clearCount != null)) }
    BackHandler { if (state.clearCount != null) model.cancelClear() else model.cancel() }
    LaunchedEffect(lifecycle) {
        // Retained Fragment ViewModels also need a fresh count after configuration recreation.
        if (lifecycle == Lifecycle.State.RESUMED && state.loaded && !state.busy && state.effect == null && !state.finished) model.load()
    }
    LaunchedEffect(state.finished, state.effect, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        if (state.finished) { dismiss(); return@LaunchedEffect }
        state.effect?.let { effect ->
            try {
                val record = model.resolve(effect.id) ?: return@LaunchedEffect
                currentCoroutineContext().ensureActive()
                if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
                if (effect.action == RssReadRecordAction.Read) open(record) else url(if (record.record.isAbsUrl()) record.record else "http://${record.record}")
                model.delivered(effect.id)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                model.failed(effect.id, error.localizedMessage ?: error.toString())
            }
        }
    }
    RssReadRecordScreen(state, model::read, model::browser, model::requestClear,
        model::confirmClear, model::cancelClear, model::cancel, model::load)
}
