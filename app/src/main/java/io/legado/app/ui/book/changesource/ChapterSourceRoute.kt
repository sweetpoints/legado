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
import io.legado.app.data.repository.ChapterSourceGroupRepository
import io.legado.app.data.repository.ChapterSourceReceipt
import io.legado.app.data.repository.ChapterSourceReceiptKind
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal data class ChapterSourceDelivery(
    val receipt: ChapterSourceReceipt,
    val book: Book? = null,
    val source: BookSource? = null,
    val chapters: List<BookChapter> = emptyList(),
)

@Composable
internal fun ChapterSourceRoute(
    model: ChapterSourceViewModel,
    canHandle: () -> Boolean,
    host: (ChapterSourceDelivery) -> Unit,
    hostMenu: (ChapterSourceMenu) -> Unit,
    editSource: (String) -> Unit,
    close: () -> Unit,
    finished: () -> Unit,
    modifier: Modifier = Modifier,
    groupsRepository: ChapterSourceGroupRepository? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val groups = remember(groupsRepository) { groupsRepository?.groups() }
    val observedGroups =
        groups?.collectAsStateWithLifecycle(initialValue = state.groups)?.value ?: state.groups
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle)
    val deliver by rememberUpdatedState(host)
    val finish by rememberUpdatedState(finished)
    var scrollTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                val key = value.pendingReceipt ?: return@collect
                if (value.loading || !ready()) return@collect
                try {
                    val receipt = model.prepareReceipt(key)
                    val prepared =
                        withContext(Dispatchers.Default) {
                            if (receipt.kind == ChapterSourceReceiptKind.Change)
                                ChapterSourceDelivery(
                                    receipt,
                                    GSON.fromJson(receipt.bookJson, Book::class.java),
                                    GSON.fromJson(receipt.sourceJson, BookSource::class.java),
                                    receipt.chapters.map {
                                        GSON.fromJson(it.json, BookChapter::class.java)
                                    },
                                )
                            else ChapterSourceDelivery(receipt)
                        }
                    currentCoroutineContext().ensureActive()
                    if (
                        ready() &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            model.state.value.pendingReceipt == key
                    ) {
                        model.consumeReceipt(receipt)
                        scrollTarget = receipt.targetPosition?.let { receipt.key to it }
                        deliver(prepared)
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
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { value ->
                if (!ready() || value.loading || value.pendingReceipt != null) return@collect
                if (value.finished) finish()
                else if (value.automation?.stage == "Ready" && !value.busy)
                    model.runAutomationIfReady()
            }
        }
    }
    val requestClose = {
        val latest = model.state.value
        when {
            latest.automation != null -> model.stopAutomation()
            latest.caching -> model.cancelCaching()
            latest.batch && latest.tocLoading -> Unit
            else -> close()
        }
    }
    val back = {
        val latest = model.state.value
        when {
            latest.automation != null -> model.stopAutomation()
            latest.caching -> model.cancelCaching()
            latest.batch && latest.tocLoading -> Unit
            latest.tocVisible -> model.hideToc()
            else -> close()
        }
    }
    BackHandler(onBack = back)
    ChapterSourceScreen(
        state.copy(groups = observedGroups),
        ChapterSourceScreenActions(
            close = requestClose,
            startStop = model::startOrStop,
            searchOpen = model::filterOpen,
            query = model::query,
            retry = model::retry,
            recover = model::retryCacheRecovery,
            menu = { action ->
                when (action) {
                    ChapterSourceMenu.Refresh -> model.refresh()
                    ChapterSourceMenu.Author -> model.toggle(ChapterSourceOption.Author)
                    ChapterSourceMenu.Info -> model.toggle(ChapterSourceOption.Info)
                    ChapterSourceMenu.Toc -> model.toggle(ChapterSourceOption.Toc)
                    ChapterSourceMenu.WordCount -> model.toggle(ChapterSourceOption.WordCount)
                    ChapterSourceMenu.ResponseTime -> model.toggle(ChapterSourceOption.ResponseTime)
                    ChapterSourceMenu.Automation -> model.stopAutomation()
                    ChapterSourceMenu.Close -> requestClose()
                    else -> hostMenu(action)
                }
            },
            group = model::group,
            openToc = model::openToc,
            hideToc = model::hideToc,
            chapter = model::chapter,
            skip = model::skip,
            cache = model::cacheSelected,
            rowAction = { id, action ->
                when (action) {
                    ChapterSourceRowAction.Top -> model.order(id, true)
                    ChapterSourceRowAction.Bottom -> model.order(id, false)
                    ChapterSourceRowAction.Disable -> model.disable(id)
                    ChapterSourceRowAction.Delete -> model.deleteSource(id)
                    ChapterSourceRowAction.Edit ->
                        state.rows.find { it.id == id }?.let { editSource(it.origin) }
                }
            },
            score = model::score,
            dismissEmpty = model::dismissEmptyGroup,
            startAutomation = model::startAutomation,
            range = model::rangeDefaults,
        ),
        modifier,
        scrollTarget,
    )
}
