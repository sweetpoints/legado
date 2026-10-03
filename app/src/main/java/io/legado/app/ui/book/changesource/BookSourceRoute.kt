package io.legado.app.ui.book.changesource

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.preferences.ChapterSourceOption
import io.legado.app.data.repository.BookSourceChangeReceipt
import io.legado.app.data.repository.ChapterSourceGroupRepository
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

internal data class BookSourceDelivery(
    val receipt: BookSourceChangeReceipt,
    val book: Book,
    val source: BookSource,
    val chapters: List<BookChapter>,
)

@Composable
internal fun BookSourceRoute(
    model: BookSourceViewModel,
    canHandle: () -> Boolean,
    host: (BookSourceDelivery) -> Unit,
    hostMenu: (BookSourceMenu) -> Unit,
    editSource: (String) -> Unit,
    close: () -> Unit,
    modifier: Modifier = Modifier,
    groupsRepository: ChapterSourceGroupRepository? = null,
    warning: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val groups = remember(groupsRepository) { groupsRepository?.groups() }
    val observedGroups =
        groups?.collectAsStateWithLifecycle(initialValue = emptyList())?.value.orEmpty()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle)
    val deliver by rememberUpdatedState(host)
    val finish by rememberUpdatedState(close)
    val showWarning by rememberUpdatedState(warning)
    var finishRequested by remember { mutableStateOf(false) }
    val requestFinish = {
        if (!finishRequested) {
            finishRequested = true
            finish()
        }
    }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (value.loading || !ready()) return@collect
                if (value.relativeWarning) {
                    model.consumeWarning()
                    showWarning()
                }
                val key = value.pendingReceipt
                if (key == null) {
                    if (value.finished) requestFinish()
                    return@collect
                }
                try {
                    val receipt = model.prepareReceipt(key)
                    val prepared =
                        withContext(Dispatchers.Default) {
                            BookSourceDelivery(
                                receipt,
                                GSON.fromJson(receipt.bookJson, Book::class.java),
                                GSON.fromJson(receipt.sourceJson, BookSource::class.java),
                                receipt.chapters.map {
                                    GSON.fromJson(it.json, BookChapter::class.java)
                                },
                            )
                        }
                    currentCoroutineContext().ensureActive()
                    if (
                        ready() &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            model.state.value.pendingReceipt == key
                    ) {
                        model.consumeReceipt(receipt)
                        deliver(prepared)
                        if (receipt.deleteAfter == null) requestFinish()
                    }
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    model.receiptFailed(error)
                }
            }
        }
    }
    BackHandler { if (model.state.value.changing) model.cancelChange() else close() }
    BookSourceScreen(
        state,
        BookSourceScreenActions(
            close,
            model::startOrStop,
            model::filterOpen,
            model::query,
            model::retry,
            { action ->
                when (action) {
                    BookSourceMenu.Refresh -> model.refreshMeasurements()
                    BookSourceMenu.Author -> model.toggle(ChapterSourceOption.Author)
                    BookSourceMenu.WordCount -> model.toggle(ChapterSourceOption.WordCount)
                    BookSourceMenu.ResponseTime -> model.toggle(ChapterSourceOption.ResponseTime)
                    BookSourceMenu.Info -> model.toggle(ChapterSourceOption.Info)
                    BookSourceMenu.Toc -> model.toggle(ChapterSourceOption.Toc)
                    BookSourceMenu.Close -> close()
                    else -> hostMenu(action)
                }
            },
            model::group,
            model::choose,
            { id, action ->
                when (action) {
                    BookSourceRowAction.Top -> model.order(id, true)
                    BookSourceRowAction.Bottom -> model.order(id, false)
                    BookSourceRowAction.Disable -> model.disable(id)
                    BookSourceRowAction.Delete -> model.deleteSource(id)
                    BookSourceRowAction.Edit ->
                        model.state.value.rows.find { it.id == id }?.let { editSource(it.origin) }
                }
            },
            model::score,
            model::dismissEmptyGroup,
            model::searchAll,
            model::confirmMismatch,
            model::dismissMismatch,
            model::cancelChange,
        ),
        modifier,
        observedGroups,
    )
}
