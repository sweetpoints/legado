package io.legado.app.ui.rss.article

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.RssCategoryVariable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class RssCategoryNative(val effect: RssCategoryEffect, val source: RssSource, val variable: RssCategoryVariable? = null)

@Composable
fun RssCategoryRoute(model: RssCategoryViewModel, landscape: Boolean, ready: () -> Boolean,
    back: () -> Unit, native: (RssCategoryNative) -> Unit,
    page: @Composable (Int, Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val deliver by rememberUpdatedState(native)
    LaunchedEffect(state.pending, state.loaded, lifecycle) {
        if (!state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        val ticket = state.pending ?: return@LaunchedEffect
        try {
            val variable = if (ticket.kind == RssCategoryEffectKind.Variable) model.variable(ticket) else null
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
            val source = model.sourceSnapshot() ?: return@LaunchedEffect
            model.delivered(ticket.nonce) ?: return@LaunchedEffect
            if (ticket.kind != RssCategoryEffectKind.Variable || variable != null) deliver(RssCategoryNative(ticket, source, variable))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
            model.delivered(ticket.nonce); model.failed(error.localizedMessage ?: "Error")
        }
    }
    RssCategoryScreen(state, landscape, RssCategoryActions(back, model::select, model::menu, model::search,
        model::draft, model::submitSearch, model::effect, model::refresh, model::switchStyle, model::clearArticles, model::retry)) { index, visible ->
        val pageOwner = rememberRssPageLifecycle(owner, visible)
        CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) { page(index, visible) }
    }
}

internal class RssPageLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun update(parent: Lifecycle.State, active: Boolean) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = if (parent == Lifecycle.State.DESTROYED) parent else
            minOf(parent, if (active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
    }
    fun dispose() { registry.currentState = Lifecycle.State.DESTROYED }
}
@Composable private fun rememberRssPageLifecycle(parent: LifecycleOwner, active: Boolean): RssPageLifecycleOwner {
    val owner = remember(parent) { RssPageLifecycleOwner() }
    val currentActive by rememberUpdatedState(active)
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> owner.update(parent.lifecycle.currentState, currentActive) }
        parent.lifecycle.addObserver(observer); owner.update(parent.lifecycle.currentState, currentActive)
        onDispose { parent.lifecycle.removeObserver(observer); owner.dispose() }
    }
    SideEffect { owner.update(parent.lifecycle.currentState, active) }
    return owner
}
