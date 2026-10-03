package io.legado.app.ui.book.source.edit

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
internal fun JsSourceEditRoute(
    model: JsSourceEditViewModel,
    onLaunch: (JsSourceEditStage, JsSourceEditState) -> Unit,
    onFinish: (JsSourceEditState) -> Unit,
    canHandle: () -> Boolean = { true },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launch by rememberUpdatedState(onLaunch)
    val finish by rememberUpdatedState(onFinish)
    val ready by rememberUpdatedState(canHandle)
    BackHandler(enabled = !state.busy) { model.cancel() }
    LaunchedEffect(model, lifecycle) {
        var finished = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { currentState ->
                if (!ready()) return@collect
                if (currentState.finished) {
                    if (!finished) {
                        finished = true
                        finish(currentState)
                    }
                } else if (
                    currentState.loaded && !currentState.busy && currentState.error == null
                ) {
                    val destination =
                        when (currentState.stage) {
                            JsSourceEditStage.READY ->
                                if (currentState.editorPath != null) {
                                    JsSourceEditStage.EDITOR_OPEN
                                } else {
                                    null
                                }
                            JsSourceEditStage.DEBUG_READY -> JsSourceEditStage.DEBUG_OPEN
                            JsSourceEditStage.LOGIN_READY -> JsSourceEditStage.LOGIN_OPEN
                            else -> null
                        }
                    if (destination != null) {
                        model.deliverLaunch(
                            destination = destination,
                            canLaunch = {
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
                            },
                            launch = { deliveredState -> launch(destination, deliveredState) },
                        )
                    }
                }
            }
        }
    }
    JsSourceEditScreen(state, model::load, model::cancel)
}
