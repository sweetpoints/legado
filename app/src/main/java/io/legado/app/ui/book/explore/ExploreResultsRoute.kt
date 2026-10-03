package io.legado.app.ui.book.explore

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.repository.ExploreResultsNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ExploreResultsRoute(
    model: ExploreResultsViewModel,
    ready: () -> Boolean,
    close: () -> Unit,
    native: (String) -> Unit,
    notice: (String) -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val state by model.state.collectAsStateWithLifecycle()
    val currentReady by rememberUpdatedState(ready)
    val currentNative by rememberUpdatedState(native)
    val currentNotice by rememberUpdatedState(notice)
    val list = rememberLazyListState()
    val context = LocalContext.current
    LaunchedEffect(model, lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) model.resume()
    }
    LaunchedEffect(state.finished, lifecycle) {
        if (state.finished && lifecycle == Lifecycle.State.RESUMED && currentReady()) close()
    }
    LaunchedEffect(state.scrollRequest, state.rows.size, state.loaded) {
        val token = state.scrollRequest
        if (!state.loaded || token == 0L) return@LaunchedEffect
        // An asynchronous load must not consume restoration against an empty, temporary list.
        if (state.rows.isEmpty()) {
            if (!state.loadingNext && !state.interruptedPage) model.scrolled(token)
            return@LaunchedEffect
        }
        val row = state.scrollTargetIndex.coerceIn(0, state.rows.lastIndex)
        list.scrollToItem(row + 1, state.scrollTargetOffset)
        model.scrolled(token)
    }
    LaunchedEffect(model, list) {
        var previousIndex = 0
        var previousOffset = 0
        snapshotFlow {
            Triple(
                list.firstVisibleItemIndex,
                list.firstVisibleItemScrollOffset,
                list.isScrollInProgress,
            )
        }
            .distinctUntilChanged()
            .collect { (index, offset, scrolling) ->
                val current = model.state.value
                if (!current.loaded || current.scrollRequest != 0L) return@collect
                val rowIndex = (index - 1).coerceAtLeast(0)
                model.scroll(current.rows.getOrNull(rowIndex)?.key, rowIndex, offset)
                val upward =
                    index < previousIndex || index == previousIndex && offset < previousOffset
                if (
                    scrolling &&
                        upward &&
                        index == 0 &&
                        owner.lifecycle.currentState == Lifecycle.State.RESUMED
                )
                    model.previous()
                previousIndex = index
                previousOffset = offset
            }
    }
    LaunchedEffect(model, state.rows.size, state.checkpoint?.hasMore, lifecycle) {
        if (!state.loaded || lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.key == "next-page" }
            .distinctUntilChanged()
            .collect { visible ->
                val current = model.state.value
                if (
                    visible &&
                        current.rows.isNotEmpty() &&
                        !current.interruptedPage &&
                        current.checkpoint?.error == null &&
                        current.checkpoint?.hasMore == true &&
                        owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        currentReady()
                ) {
                    model.next()
                }
            }
    }
    val nonce = state.checkpoint?.detailNonce
    LaunchedEffect(model, nonce, lifecycle, state.loaded) {
        if (
            nonce == null ||
                !state.loaded ||
                lifecycle != Lifecycle.State.RESUMED ||
                !currentReady()
        )
            return@LaunchedEffect
        try {
            deliverExploreBookDetail(
                prepare = { model.prepareDetail(nonce) },
                claim = { model.detailClaimed(nonce) },
                defer = { model.detailDeferred(nonce) },
                handled = { model.detailHandled(nonce) },
                ready = {
                    owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady()
                },
                native = currentNative,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (owner.lifecycle.currentState == Lifecycle.State.RESUMED && currentReady())
                currentNotice(error.localizedMessage ?: error.javaClass.simpleName)
        }
    }
    val checkpoint = state.checkpoint
    LaunchedEffect(model, checkpoint?.messageId, lifecycle) {
        val id = checkpoint?.messageId ?: return@LaunchedEffect
        if (lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        val message =
            when {
                checkpoint.addedCount != null ->
                    context.getString(
                        R.string.add_loaded_books_to_bookshelf_result,
                        checkpoint.addedCount,
                        checkpoint.skippedCount ?: 0,
                    )
                checkpoint.notice == ExploreResultsNotice.AlreadyAdding ->
                    context.getString(R.string.add_loaded_books_to_bookshelf_in_progress)
                checkpoint.notice == ExploreResultsNotice.EmptyResults ->
                    context.getString(R.string.no_loaded_books_to_add)
                else -> checkpoint.message
            }
        if (
            message != null &&
                model.messageDelivered(id) &&
                owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                currentReady()
        )
            currentNotice(message)
    }
    val actions =
        remember(model) {
            ExploreResultsActions(
                model::close,
                model::toggleCategories,
                model::category,
                { model.next(force = true) },
                model::previous,
                model::retryLoad,
                model::showPagePicker,
                model::pagePicker,
                model::cancelPagePicker,
                model::confirmPage,
                model::askAdd,
                model::cancelAdd,
                model::confirmAdd,
                model::detail,
            )
        }
    ExploreResultsScreen(state, actions, list)
}
