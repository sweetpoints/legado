package io.legado.app.ui.rss.read

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.RssReaderSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun RssReaderRoute(
    model: RssReaderViewModel,
    images: RssReaderImageViewModel,
    kernel: String,
    imageOwner: String?,
    fullscreen: Boolean,
    ready: () -> Boolean,
    back: () -> Unit,
    document: (RssReaderSnapshot) -> Unit,
    native: (RssReaderEffect) -> Unit,
    imageEffect: (RssReaderImageEffect, String?) -> Unit,
    snackbar: @Composable () -> Unit = {},
    browser: @Composable () -> Unit,
    customVideo: @Composable () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val imageState by images.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val render by rememberUpdatedState(document)
    val deliver by rememberUpdatedState(native)
    val deliverImage by rememberUpdatedState(imageEffect)
    DisposableEffect(model, kernel) {
        model.attachKernel(kernel)
        onDispose { model.detachKernel(kernel) }
    }
    LaunchedEffect(state.document, state.loaded, lifecycle) {
        if (!state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady())
            return@LaunchedEffect
        val snapshot = model.document(state.document, kernel) ?: return@LaunchedEffect
        currentCoroutineContext().ensureActive()
        if (
            owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                currentReady() &&
                model.documentDelivered(state.document, kernel)
        )
            runCatching { render(snapshot) }
                .onFailure { model.failed(it.localizedMessage ?: it.javaClass.simpleName) }
    }
    LaunchedEffect(state.pending, state.loaded, lifecycle) {
        if (!state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady())
            return@LaunchedEffect
        val effect = state.pending ?: return@LaunchedEffect
        currentCoroutineContext().ensureActive()
        if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady())
            model.delivered(effect.nonce)?.let { value ->
                runCatching { deliver(value) }
                    .onFailure { model.failed(it.localizedMessage ?: it.javaClass.simpleName) }
            }
    }
    LaunchedEffect(imageState.pending, lifecycle, imageOwner) {
        if (imageOwner == null || lifecycle != Lifecycle.State.RESUMED || !currentReady())
            return@LaunchedEffect
        val effect = imageState.pending ?: return@LaunchedEffect
        currentCoroutineContext().ensureActive()
        if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
            return@LaunchedEffect
        val accepted =
            when (effect.kind) {
                RssReaderImageEffectKind.Picker -> images.pickerDelivered(effect.nonce)
                RssReaderImageEffectKind.Saved -> images.delivered(effect.nonce)
            }
        if (accepted) deliverImage(effect, imageState.previousDirectory)
    }
    RssReaderScreen(
        state,
        fullscreen,
        RssReaderActions(back, model::menu, model::action, model::retry),
        snackbar,
        browser,
        customVideo,
    )
}
