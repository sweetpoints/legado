package io.legado.app.ui.main.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.entities.BookSourcePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ExploreHomePrepared(
    val effect: ExploreHomeEffect,
    val searchSource: BookSourcePart?,
)

@Composable
internal fun ExploreHomeRoute(
    model: ExploreHomeViewModel,
    ready: () -> Boolean,
    onNative: (ExploreHomePrepared) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val currentReady by rememberUpdatedState(ready)
    val currentNative by rememberUpdatedState(onNative)
    val focus = LocalFocusManager.current
    LaunchedEffect(model, owner) {
        dispatchExploreHomeEffects(
            model,
            owner.lifecycle,
            { currentReady() },
            { currentNative(it) },
            { focus.clearFocus() },
        )
    }

    val actions =
        remember(model) {
            ExploreHomeActions(
                query = model::query,
                expand = model::expand,
                action = { action, url ->
                    when (action) {
                        "top" -> model.top(url)
                        "delete" -> model.requestDelete(url)
                        "refresh" -> model.refresh(url)
                        else -> model.effect(action, url)
                    }
                },
                control = model::control,
                value = model::value,
                delete = model::delete,
                dismiss = model::dismiss,
                retry = model::retry,
            )
        }
    ExploreHomeScreen(state, actions)
}

internal suspend fun dispatchExploreHomeEffects(
    model: ExploreHomeViewModel,
    lifecycle: Lifecycle,
    ready: () -> Boolean,
    onNative: (ExploreHomePrepared) -> Unit,
    onPause: () -> Unit = {},
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        try {
            model.observeResumed()
            model.state.collect { current ->
                val effect = current.effect
                if (effect != null && !current.busy && current.error == null) {
                    try {
                        val prepared = ExploreHomePrepared(effect, model.prepareSearch(effect))
                        currentCoroutineContext().ensureActive()
                        model.deliver(
                            effect,
                            {
                                lifecycle.currentState == Lifecycle.State.RESUMED && ready()
                            },
                        ) {
                            onNative(prepared)
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        model.hostFailure(failure)
                    }
                }
            }
        } finally {
            model.pause()
            onPause()
        }
    }
}
