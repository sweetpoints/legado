package io.legado.app.ui.book.manage

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.entities.Book
import io.legado.app.model.bookshelf.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PreparedShelfEffect(
    val receipt: ShelfManagementReceipt,
    val books: List<Book> = emptyList(),
)

@Composable
internal fun BookshelfManagementRoute(
    model: BookshelfManagementViewModel,
    ready: () -> Boolean,
    effect: (PreparedShelfEffect) -> Unit,
    back: () -> Unit,
    copy: (String) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready)
    val handle by rememberUpdatedState(effect)
    val leave by rememberUpdatedState(back)
    val clipboard by rememberUpdatedState(copy)
    var dragging by remember { mutableStateOf<String?>(null) }
    val goBack: () -> Unit = {
        val current = model.state.value
        when {
            current.selecting || current.reordering -> {
                model.endSelectionGesture(cancel = true)
                model.finishReorder(cancel = true)
                dragging = null
            }
            current.busy -> model.cancelOperation()
            current.pendingCommit -> Unit
            else -> leave()
        }
    }
    BackHandler { goBack() }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val receipt = value.draft?.effects?.firstOrNull()
                if (
                    receipt != null &&
                        available() &&
                        !value.loading &&
                        !value.failed &&
                        !value.busy &&
                        !value.pendingCommit &&
                        !value.writeFailed &&
                        !value.interrupted &&
                        value.error == null
                ) {
                    val books =
                        try {
                            if (
                                receipt.effect in
                                    listOf(
                                        ShelfManagementEffect.OpenBook,
                                        ShelfManagementEffect.UpdateToc,
                                    )
                            )
                                model.prepareBooks(receipt)
                            else emptyList()
                        } catch (canceled: CancellationException) {
                            throw canceled
                        } catch (error: Exception) {
                            currentCoroutineContext().ensureActive()
                            model.preparationFailed(error)
                            return@collect
                        }
                    currentCoroutineContext().ensureActive()
                    if (
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            available() &&
                            model.consumeEffect(receipt.id)
                    )
                        handle(PreparedShelfEffect(receipt, books))
                }
            }
        }
    }
    val actions =
        remember(model) {
            BookshelfManagementActions(
                { goBack() },
                model::query,
                model::group,
                model::toggle,
                model::selectAll,
                model::inverse,
                model::selectInterval,
                { action ->
                    when (action) {
                        ShelfManagementAction.GroupReplace ->
                            model.pickGroup(ShelfGroupMutation.Replace)
                        ShelfManagementAction.GroupAdd -> model.pickGroup(ShelfGroupMutation.Add)
                        ShelfManagementAction.GroupRemove ->
                            model.pickGroup(ShelfGroupMutation.Remove)
                        ShelfManagementAction.ChangeSource -> model.pickSource()
                        ShelfManagementAction.Delete,
                        ShelfManagementAction.RestoreNetworkCovers,
                        ShelfManagementAction.RestoreSourceCovers,
                        ShelfManagementAction.CreateTasks -> model.confirmAction(action)
                        else -> model.execute(action)
                    }
                },
                {
                    model.confirmAction(
                        ShelfManagementAction.Delete,
                        listOf(it),
                        model.state.value.snapshot
                            ?.books
                            ?.firstOrNull { book -> book.id == it }
                            ?.local == true,
                    )
                },
                { id, group -> model.pickGroup(ShelfGroupMutation.Replace, listOf(id), group) },
                model::openBook,
                model::manageGroups,
                { model.execute(ShelfManagementAction.ToggleTitle, enabled = it) },
                model::retry,
                model::retryOperationConfirmed,
                model::dismissConfirmation,
                model::executeConfirmed,
                model::deleteOriginal,
                model::confirmationText,
                model::cancelOperation,
                model::beginSelectionGesture,
                { first, last ->
                    val ids = model.state.value.snapshot?.books.orEmpty().map { it.id }
                    model.selectionRange(ids.indexOf(first), ids.indexOf(last))
                },
                { model.endSelectionGesture() },
                { id -> model.beginReorder(id).also { if (it) dragging = id } },
                { target -> dragging?.let { model.reorder(it, target) } },
                {
                    model.finishReorder()
                    dragging = null
                },
                {
                    model.endSelectionGesture(cancel = true)
                    model.finishReorder(cancel = true)
                    dragging = null
                },
                model::closeExportResult,
                { clipboard(it) },
            )
        }
    BookshelfManagementScreen(state, actions)
}
