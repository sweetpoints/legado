package io.legado.app.ui.rss.favorites

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.GlideRssFavoriteImageRepository
import io.legado.app.data.repository.RssFavoriteImageRepository
import kotlinx.coroutines.*

@Composable
internal fun RssFavoriteListRoute(
    model: RssFavoriteListViewModel,
    showToolbar: Boolean,
    canDeliver: () -> Boolean,
    read: (RssStar) -> Unit,
    back: () -> Unit,
    imageRepository: RssFavoriteImageRepository? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by
        owner.lifecycle.currentStateFlow.collectAsStateWithLifecycle(
            minActiveState = Lifecycle.State.CREATED
        )
    val ready by rememberUpdatedState(canDeliver)
    val navigate by rememberUpdatedState(read)
    val context = LocalContext.current.applicationContext
    val images =
        remember(context, imageRepository) {
            imageRepository ?: GlideRssFavoriteImageRepository(context)
        }
    BackHandler(enabled = state.confirmation != null) { model.cancelConfirmation() }
    LaunchedEffect(state.pendingRead, lifecycle) {
        val id = state.pendingRead ?: return@LaunchedEffect
        if (lifecycle != Lifecycle.State.RESUMED || !ready()) return@LaunchedEffect
        try {
            val article = model.resolveRead(id)
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState != Lifecycle.State.RESUMED || !ready())
                return@LaunchedEffect
            model.readDelivered(id)
            article?.let(navigate)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // Invalid/deleted requests and throwing native navigation must not block later clicks.
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && ready())
                model.readDelivered(id)
        }
    }
    RssFavoriteListScreen(
        state,
        showToolbar,
        back,
        model::selectGroup,
        model::read,
        model::requestDelete,
        model::requestDeleteGroup,
        model::requestDeleteAll,
        model::confirmDelete,
        model::cancelConfirmation,
        model::load,
        model::scrollPosition,
        model::scrolled,
    ) {
        RssFavoriteListImage(it, images)
    }
}
