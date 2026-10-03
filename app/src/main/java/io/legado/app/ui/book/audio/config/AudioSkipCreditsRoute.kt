package io.legado.app.ui.book.audio.config

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.AudioSkipCreditsDraft
import kotlinx.coroutines.flow.collect

@Composable
internal fun AudioSkipCreditsRoute(
    model: AudioSkipCreditsViewModel,
    canHandle: () -> Boolean,
    updateBook: (AudioSkipCreditsDraft) -> Unit,
    close: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle)
    val update by rememberUpdatedState(updateBook)
    val dismiss by rememberUpdatedState(close)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.state.collect { value ->
                if (ready() && !value.loading) value.draft?.let { update(it) }
            }
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value -> if (value.finished && ready()) dismiss() }
        }
    }
    BackHandler { model.requestClose() }
    AudioSkipCreditsScreen(
        state,
        model::scope,
        model::opening,
        model::closing,
        model::retry,
        model::requestClose,
    )
}
