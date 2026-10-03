package io.legado.app.ui.book.bookmark

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.repository.AllBookmarksDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun AllBookmarksRoute(
    model: AllBookmarksViewModel,
    ready: () -> Boolean,
    close: () -> Unit,
    open: (AllBookmarksDestination, Int) -> Unit,
    directory: (Boolean) -> Unit,
    notice: (String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val currentOpen by rememberUpdatedState(open)
    val currentDirectory by rememberUpdatedState(directory)
    val currentNotice by rememberUpdatedState(notice)
    val exported = stringResource(R.string.export_success)
    val unknown = stringResource(R.string.unknown_error)
    LaunchedEffect(state.effect, lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        val value = state.effect ?: return@LaunchedEffect
        try {
            when (value.type) {
                AllBookmarksEffectType.Open -> {
                    val destination = model.resolveOpen(value)
                    currentCoroutineContext().ensureActive()
                    if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                        return@LaunchedEffect
                    model.delivered(value.nonce) ?: return@LaunchedEffect
                    if (destination != null)
                        currentOpen(
                            destination,
                            model.state.value.rows
                                .indexOfFirst { it.id == value.bookmark }
                                .coerceAtLeast(0),
                        )
                }
                AllBookmarksEffectType.Directory -> {
                    model.delivered(value.nonce) ?: return@LaunchedEffect
                    currentDirectory(value.markdown)
                }
                AllBookmarksEffectType.Exported -> {
                    model.delivered(value.nonce) ?: return@LaunchedEffect
                    currentNotice(exported)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                return@LaunchedEffect
            model.delivered(value.nonce)
            model.deliveryFailed(value, error.localizedMessage ?: unknown)
        }
    }
    LaunchedEffect(state.error, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED && currentReady())
            state.error?.let {
                model.clearError()
                currentNotice(it)
            }
    }
    AllBookmarksScreen(
        state,
        AllBookmarksActions(
            close,
            model::open,
            model::requestExport,
            model::scroll,
            model::observe,
        ),
    )
}
