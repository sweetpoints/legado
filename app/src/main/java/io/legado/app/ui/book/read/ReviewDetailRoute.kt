package io.legado.app.ui.book.read

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.ui.widget.dialog.photo.PhotoImageLoader
import io.legado.app.utils.windowSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import splitties.systemservices.windowManager

@Composable
internal fun ReviewDetailRoute(
    viewModel: ReviewDetailViewModel,
    totalCount: Int,
    sourceKey: String,
    imageLoader: PhotoImageLoader,
    audio: ReviewDetailAudio,
    canHandle: () -> Boolean,
    onPhoto: (String) -> Unit,
    onToast: (String) -> Unit,
    onHeight: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by
        viewModel.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val audioState by
        audio.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val rowCount = remember(state.snapshot, state.loadingReplies) { state.rows.size }
    val latestRowCount by rememberUpdatedState(rowCount)
    val ready by rememberUpdatedState(canHandle)
    val photo by rememberUpdatedState(onPhoto)
    val toast by rememberUpdatedState(onToast)
    val height by rememberUpdatedState(onHeight)
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val list = rememberLazyListState()
    val dimensions = remember(sourceKey) { ReviewImageDimensions() }
    DisposableEffect(audio) { onDispose { audio.release() } }
    BackHandler { viewModel.cancel() }
    LaunchedEffect(viewModel, list) {
        var previous = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { position ->
                val forward =
                    position.first > previous.first ||
                        position.first == previous.first && position.second > previous.second
                previous = position
                if (
                    forward &&
                        list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                            it >= latestRowCount - 3
                        } == true
                )
                    viewModel.nextPage()
            }
    }
    LaunchedEffect(viewModel, lifecycle, audio) {
        var closed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.state.collect { value ->
                if (!ready()) return@collect
                height(value.heightRatio)
                if (value.finished) {
                    if (!closed) {
                        closed = true
                        close()
                    }
                    return@collect
                }
                // Restored effect URLs are resolved from the disk-backed snapshot. Wait until it is
                // available.
                if (value.loading && value.snapshot.page == 0) return@collect
                val effect = value.effects.firstOrNull() ?: return@collect
                fun deliverable() =
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        ready() &&
                        !viewModel.state.value.finished &&
                        viewModel.state.value.effects.any { it.id == effect.id }
                when (effect.action) {
                    ReviewDetailAction.Photo -> {
                        val url = viewModel.effectUrl(effect)
                        viewModel.consumeEffect(effect.id)
                        if (url != null) photo(url)
                    }
                    ReviewDetailAction.Toast -> {
                        viewModel.consumeEffect(effect.id)
                        toast(
                            if (effect.message == "review_rule_missing")
                                resources.getString(R.string.review_rule_missing)
                            else
                                effect.message.ifBlank {
                                    resources.getString(R.string.load_over_time)
                                }
                        )
                    }
                    ReviewDetailAction.Audio -> {
                        val url = viewModel.effectUrl(effect)
                        if (url == null) {
                            viewModel.consumeEffect(effect.id)
                            return@collect
                        }
                        try {
                            val item =
                                if (audio.state.value.url == url) null else viewModel.mediaItem(url)
                            if (deliverable()) {
                                viewModel.consumeEffect(effect.id)
                                if (item != null || audio.state.value.url == url)
                                    audio.toggle(url, item)
                                else toast(resources.getString(R.string.review_rule_missing))
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (deliverable()) {
                                viewModel.consumeEffect(effect.id)
                                toast(
                                    error.localizedMessage
                                        ?: resources.getString(R.string.load_over_time)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    ReviewDetailScreen(
        state,
        totalCount,
        sourceKey,
        list,
        audioState,
        imageLoader,
        dimensions,
        viewModel::replies,
        viewModel::photo,
        viewModel::audio,
        viewModel::cancel,
        viewModel::toggleHeight,
        { delta ->
            val pixels = context.windowManager.windowSize.heightPixels
            if (pixels > 0) viewModel.resize(-delta / pixels)
        },
        viewModel::retry,
        modifier,
    )
}
