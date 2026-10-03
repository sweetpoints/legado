package io.legado.app.ui.book.import.remote

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.entities.Book
import io.legado.app.model.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PreparedRemoteLibraryEffect(
    val receipt: RemoteLibraryReceipt,
    val book: Book? = null,
)

@Composable
internal fun RemoteLibraryRoute(
    model: RemoteLibraryViewModel,
    ready: () -> Boolean,
    effect: (PreparedRemoteLibraryEffect) -> Unit,
    finish: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val available by rememberUpdatedState(ready)
    val host by rememberUpdatedState(effect)
    val close by rememberUpdatedState(finish)
    val back: () -> Unit = {
        val value = model.state.value
        when {
            value.busy || value.pendingCommit -> Unit
            value.interrupted -> model.discardTask()
            value.draft?.confirmation?.kind == RemoteLibraryPrompt.StorageHelp ->
                model.cancelStorageHelp()
            value.failed -> close()
            else -> if (!model.goBackDirectory()) close()
        }
    }
    BackHandler { back() }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val receipt = value.draft?.effects?.firstOrNull() ?: return@collect
                if (
                    !available() ||
                        value.loading ||
                        value.failed &&
                            receipt.effect !in
                                listOf(
                                    RemoteLibraryEffect.Servers,
                                    RemoteLibraryEffect.Help,
                                    RemoteLibraryEffect.Log,
                                    RemoteLibraryEffect.Toast,
                                ) ||
                        value.busy ||
                        value.pendingCommit ||
                        value.writeFailed ||
                        value.interrupted &&
                            receipt.effect !in
                                listOf(
                                    RemoteLibraryEffect.PickStorage,
                                    RemoteLibraryEffect.Toast,
                                ) ||
                        receipt.effect == RemoteLibraryEffect.OpenBook && value.error != null
                )
                    return@collect
                val book =
                    try {
                        receipt.bookId?.let { model.prepareBook(it) }
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
                    host(PreparedRemoteLibraryEffect(receipt, book))
            }
        }
    }
    val actions =
        remember(model) {
            RemoteLibraryActions(
                { back() },
                { model.goBackDirectory() },
                model::query,
                { model.refresh() },
                model::sort,
                model::menu,
                model::openDirectory,
                model::toggle,
                model::read,
                model::reimport,
                model::selectAll,
                model::inverse,
                model::importSelected,
                model::retry,
                model::confirm,
                model::dismissConfirmation,
                model::cancelStorageHelp,
                model::archiveChoice,
                model::retryTaskConfirmed,
                model::discardTask,
            )
        }
    RemoteLibraryScreen(state, actions)
}
