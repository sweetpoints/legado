package io.legado.app.ui.highlight

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.repository.*
import java.io.File
import kotlinx.coroutines.*

/** Only this lifecycle route/host dispatches reader callbacks and platform navigation. */
@Composable
fun HighlightManagementRoute(
    model: HighlightManagementViewModel,
    transfer: HighlightManagementTransferRepository,
    onClose: () -> Unit,
    onDeliver: (HighlightManagementEffect, ByteArray?, File?) -> Unit,
    onError: (String) -> Unit,
    exportSummary: String? = null,
    canHandle: () -> Boolean = { true },
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val deliver by rememberUpdatedState(onDeliver)
    val error by rememberUpdatedState(onError)
    val available by rememberUpdatedState(canHandle)
    var closed by remember(model) { mutableStateOf(false) }
    var failedToken by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, owner, transfer) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (!available()) return@collect
                if (current.closed && !closed) {
                    closed = true
                    close()
                    return@collect
                }
                if (!current.loaded || !current.rowsReady || current.busy) return@collect
                val effect = current.draft.effects.firstOrNull() ?: return@collect
                if (effect.token == failedToken && current.error != null) return@collect
                var file: File? = null
                var dispatched = false
                try {
                    val bytes =
                        if (effect.action == HighlightManagementAction.Export)
                            transfer.export(effect)
                        else null
                    if (effect.action == HighlightManagementAction.Share)
                        file = transfer.share(effect)
                    currentCoroutineContext().ensureActive()
                    if (
                        !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                            !available()
                    )
                        return@collect
                    model
                        .consume(effect.token) {
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                available()
                        }
                        ?.let { claimed ->
                            currentCoroutineContext().ensureActive()
                            dispatched = true
                            deliver(claimed, bytes, file)
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    // A bad payload must not permanently block later actions. Explicitly redo
                    // export/share.
                    runCatching {
                        model.consume(effect.token) {
                            owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                available()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (effect.action == HighlightManagementAction.Export)
                        model.exportResult(null, effect.token)
                    failedToken = effect.token
                    model.deliveryFailure(failure)
                    error(failure.localizedMessage ?: "Error")
                } finally {
                    if (!dispatched) withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                model.cancelGesture()
                withContext(NonCancellable) { runCatching { model.flush() } }
            }
        }
    }
    val actions =
        remember(model) {
            var anchor: String? = null
            HighlightManagementActions(
                back = model::close,
                action = model::action,
                select = model::select,
                selectAll = model::selectAll,
                enable = model::enable,
                enableSelection = model::enableSelection,
                moveEdge = model::move,
                moveSelection = model::moveSelection,
                requestDelete = model::requestDelete,
                confirmDelete = model::deleteConfirmed,
                dismissDelete = model::dismissDelete,
                filter = model::filter,
                export = model::export,
                share = model::share,
                retry = model::retry,
                beginReorder = model::beginReorder,
                move = model::reorder,
                finishReorder = model::finishReorder,
                beginSlide = {
                    anchor = it
                    model.beginRange()
                    model.range(setOf(it))
                },
                slideTo = { target ->
                    val rows = model.state.value.visible
                    val start = rows.indexOfFirst { it.uuid == anchor }
                    val end = rows.indexOfFirst { it.uuid == target }
                    if (start >= 0 && end >= 0)
                        model.range(
                            rows.subList(minOf(start, end), maxOf(start, end) + 1).mapTo(
                                linkedSetOf()
                            ) {
                                it.uuid
                            }
                        )
                },
                finishSlide = {
                    anchor = null
                    model.finishRange()
                },
                cancelGesture = {
                    anchor = null
                    model.cancelGesture()
                },
                dismissExport = model::dismissExportResult,
                copyExport = model::copyExportResult,
            )
        }
    BackHandler { model.close() }
    HighlightManagementScreen(state, actions, exportSummary)
}
