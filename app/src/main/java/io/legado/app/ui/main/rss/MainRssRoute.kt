package io.legado.app.ui.main.rss

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.repository.FileRssReaderLaunchRepository
import io.legado.app.data.repository.MainRssPrepared
import io.legado.app.data.repository.RssArticleImageRepository
import io.legado.app.data.repository.RssReaderLaunchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
fun MainRssRoute(
    model: MainRssViewModel,
    images: RssArticleImageRepository,
    ready: () -> Boolean,
    native: (MainRssPrepared, String?) -> Unit,
    launches: RssReaderLaunchRepository = remember { FileRssReaderLaunchRepository() },
) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val state by model.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val currentReady by rememberUpdatedState(ready)
    val deliver by rememberUpdatedState(native)
    LaunchedEffect(model) { model.bind() }
    LaunchedEffect(model, lifecycle) { model.visible(lifecycle == Lifecycle.State.RESUMED) }
    DisposableEffect(model) { onDispose { model.visible(false) } }
    var retryEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.pending, state.loaded, state.active, lifecycle, retryEpoch) {
        if (
            !state.loaded ||
                !state.active ||
                lifecycle != Lifecycle.State.RESUMED ||
                !currentReady()
        )
            return@LaunchedEffect
        val pending = state.pending ?: return@LaunchedEffect
        try {
            deliverMainRssRequest(
                launches,
                resolve = { model.resolve(pending.nonce) },
                ready = {
                    owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady()
                },
                acknowledge = { model.delivered(pending.nonce) },
                missing = model::missing,
                native = deliver,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady())
                return@LaunchedEffect
            model.failed(error.localizedMessage ?: error.javaClass.simpleName)
        }
    }
    val actions =
        remember(model) {
            MainRssActions(
                model::query,
                model::action,
                model::top,
                model::disable,
                model::requestDelete,
                model::cancelDelete,
                model::confirmDelete,
                {
                    model.retry()
                    retryEpoch++
                },
                model::scroll,
            )
        }
    MainRssScreen(
        state.copy(active = state.active && lifecycle == Lifecycle.State.RESUMED),
        actions,
        images,
    )
}
