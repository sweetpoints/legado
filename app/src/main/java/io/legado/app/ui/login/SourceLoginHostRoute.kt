package io.legado.app.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.SourceLoginRequest
import io.legado.app.data.repository.SourceLoginSnapshot

@Composable
internal fun SourceLoginHostRoute(model: SourceLoginHostViewModel, ready: () -> Boolean,
    initialized: (SourceLoginRequest, SourceLoginSnapshot) -> Unit, form: () -> Unit,
    close: () -> Unit, error: (String?) -> Unit, external: (String) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val currentReady by rememberUpdatedState(ready)
    val currentInitialized by rememberUpdatedState(initialized)
    val showForm by rememberUpdatedState(form)
    val finish by rememberUpdatedState(close)
    val fail by rememberUpdatedState(error)
    val active = lifecycle == Lifecycle.State.RESUMED && currentReady()
    LaunchedEffect(state, active) {
        if (!active || !currentReady()) return@LaunchedEffect
        if (state.missing || state.error != null) { fail(state.error); finish(); return@LaunchedEffect }
        model.inputs()?.let { (request, snapshot) ->
            currentInitialized(request, snapshot)
            if (state.form) showForm()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f))) {
        val snapshot = model.inputs()?.second
        if (state.loaded && !state.form && snapshot != null) SourceWebLoginRoute(snapshot, ready, close, close, external)
    }
}
