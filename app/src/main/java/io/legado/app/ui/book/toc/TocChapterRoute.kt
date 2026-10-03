package io.legado.app.ui.book.toc

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun TocChapterRoute(
    model: TocChapterViewModel,
    ready: () -> Boolean,
    open: (TocChapterDelivery) -> Unit,
    active: Boolean = true,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val currentOpen by rememberUpdatedState(open)
    LaunchedEffect(state.open, state.loaded, lifecycle, active) {
        if (!active || !state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady())
            return@LaunchedEffect
        val value = state.open ?: return@LaunchedEffect
        try {
            val row = model.resolve(value)
            currentCoroutineContext().ensureActive()
            if (
                !active ||
                    owner.lifecycle.currentState != Lifecycle.State.RESUMED ||
                    !currentReady()
            )
                return@LaunchedEffect
            model.delivered(value.nonce) ?: return@LaunchedEffect
            if (row != null) currentOpen(row)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (
                !active ||
                    owner.lifecycle.currentState != Lifecycle.State.RESUMED ||
                    !currentReady()
            )
                return@LaunchedEffect
            model.delivered(value.nonce)
            model.failed(error.localizedMessage ?: "Error")
        }
    }
    TocChapterScreen(
        state,
        TocChapterActions(
            model::request,
            model::toggle,
            model::scrolled,
            model::locate,
            model::top,
            model::bottom,
            model::retry,
        ),
        active,
    )
}
