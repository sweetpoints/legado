package io.legado.app.ui.book.info.edit

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.*
import kotlinx.coroutines.*

@Composable
fun BookMetadataEditorRoute(
    model: BookMetadataEditorViewModel,
    onClose: (Boolean) -> Unit,
    onSaved: (BookMetadataSnapshot, List<BookHighlight>) -> Unit,
    onNavigate: (BookMetadataNavigation, BookMetadataSnapshot) -> Unit,
    onError: (String) -> Unit,
    canHandle: () -> Boolean = { true },
    reader: BookMetadataReaderRepository = RoomBookMetadataReaderRepository(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    val saved by rememberUpdatedState(onSaved)
    val navigate by rememberUpdatedState(onNavigate)
    val error by rememberUpdatedState(onError)
    val ready by rememberUpdatedState(canHandle)
    val preparation by rememberUpdatedState(reader)
    var closed by remember(model) { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                if (!ready()) return@collect
                if (current.closed || current.finished) {
                    if (!closed) {
                        closed = true
                        close(current.finished)
                    }
                    return@collect
                }
                if (!current.loaded || current.busy) return@collect
                val draft = current.draft ?: return@collect
                val token = draft.completion?.token ?: draft.navigation?.token ?: return@collect
                if (failed == token && current.error != null) return@collect
                try {
                    val available = {
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ready()
                    }
                    draft.completion?.let { completion ->
                        val highlights = preparation.loadHighlights(completion.book.bookUrl)
                        currentCoroutineContext().ensureActive()
                        if (available())
                            model.consumeCompletion(completion.token, available)?.let {
                                saved(it.book, highlights)
                                model.close()
                            }
                    }
                    draft.navigation?.let { pending ->
                        model.consumeNavigation(pending.token, available)?.let {
                            navigate(it, checkNotNull(draft.original))
                        }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    failed = token
                    draft.navigation
                        ?.takeIf { it.action == BookMetadataAction.PickCover }
                        ?.let { model.coverResult(it.token, null) }
                    model.failure(failure)
                    error(failure.localizedMessage ?: "Error")
                }
            }
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { runCatching { model.flush() } }
            }
        }
    }
    val actions =
        remember(model) {
            BookMetadataEditorActions(
                model::close,
                model::save,
                model::text,
                model::type,
                model::refreshCover,
                model::navigate,
                model::retry,
                model::reload,
            )
        }
    BackHandler { model.close() }
    BookMetadataEditorScreen(state, actions)
}
