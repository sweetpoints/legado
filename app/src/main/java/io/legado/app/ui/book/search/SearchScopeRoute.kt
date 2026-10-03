package io.legado.app.ui.book.search

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect

@Composable
internal fun SearchScopeRoute(
    model: SearchScopeViewModel,
    canHandle: () -> Boolean,
    onResult: (SearchScopeResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle)
    val host by rememberUpdatedState(onResult)
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.setActive(true)
            try {
                awaitCancellation()
            } finally {
                model.setActive(false)
            }
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val result = value.result ?: return@collect
                if (!ready()) return@collect
                model.consume(result)
                host(result)
            }
        }
    }
    SearchScopeScreen(
        state,
        model::tab,
        model::group,
        model::source,
        model::query,
        model::expandSearch,
        model::retry,
        { model.confirm(true) },
        model::cancel,
        { model.confirm() },
        modifier,
    )
}
