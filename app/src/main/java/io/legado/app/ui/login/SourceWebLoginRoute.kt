package io.legado.app.ui.login

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.repository.SourceLoginSnapshot

@Composable
internal fun SourceWebLoginRoute(snapshot: SourceLoginSnapshot, ready: () -> Boolean, back: () -> Unit,
    finish: () -> Unit, external: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val complete by rememberUpdatedState(finish)
    val navigate by rememberUpdatedState(external)
    val source = checkNotNull(snapshot.source)
    val controller = remember(snapshot, context) { SourceWebLoginController(context, source, snapshot.headers) }
    val state by controller.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val active = lifecycle == Lifecycle.State.RESUMED && currentReady()
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(controller, lifecycle) { if (lifecycle == Lifecycle.State.RESUMED) controller.resume() else controller.pause() }
    LaunchedEffect(state.completed, active) { if (state.completed && active && currentReady()) complete() }
    LaunchedEffect(state.checking, active) {
        if (state.checking && active) snackbar.showSnackbar(context.getString(R.string.check_host_cookie))
    }
    LaunchedEffect(state.external, active) {
        val url = state.external ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        val action = snackbar.showSnackbar(context.getString(R.string.jump_to_another_app),
            actionLabel = context.getString(R.string.confirm), duration = SnackbarDuration.Long)
        if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady() && controller.state.value.external == url) {
            controller.consumeExternal()
            if (action == SnackbarResult.ActionPerformed) navigate(url)
        }
    }
    SourceWebLoginScreen(source.getTag(), state, active, snackbar, back, controller::check) {
        AndroidView(factory = { (controller.webView.parent as? ViewGroup)?.removeView(controller.webView); controller.webView },
            modifier = Modifier.fillMaxSize())
    }
}
