package io.legado.app.ui.rss.article

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun RssArticlesPageRoute(model: RssArticlesPageViewModel, parameters: RssArticlesParameters,
    style: Int, landscape: Boolean, active: Boolean, ready: () -> Boolean,
    read: (RssArticlesRead) -> Unit, images: RssArticleImageRepository,
    reader: RssArticlesReadRepository) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val currentActive by rememberUpdatedState(active)
    val deliver by rememberUpdatedState(read)
    LaunchedEffect(parameters) { model.bind(parameters) }
    LaunchedEffect(active) { model.active(active) }
    DisposableEffect(model) { onDispose { model.active(false) } }
    LaunchedEffect(state.open, state.loaded, lifecycle, active) {
        if (!active || !state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        val ticket = state.open ?: return@LaunchedEffect
        try {
            val value = model.resolvePrepared(ticket, reader); currentCoroutineContext().ensureActive()
            if (!currentActive || owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
            model.delivered(ticket.nonce) ?: return@LaunchedEffect
            if (value != null) deliver(value)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!currentActive || owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
            model.delivered(ticket.nonce); model.failed(error.localizedMessage ?: "Error")
        }
    }
    RssArticlesPageScreen(state, style, landscape, active, parameters.preload,
        model::refresh, model::more, model::retry, model::open, model::position, model::scrolled,
        image = { row, modifier, natural, keep ->
            RssArticleImage(row, images, modifier, natural, keep, parameters.contentRevision)
        })
}
