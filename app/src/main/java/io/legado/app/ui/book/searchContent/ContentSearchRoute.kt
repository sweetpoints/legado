package io.legado.app.ui.book.searchContent

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import io.legado.app.model.book.ContentSearchMatch

internal data class ReaderContentSearchDelivery(val selected: SearchResult, val results: List<SearchResult>, val index: Int)
internal fun ContentSearchMatch.readerResult() = SearchResult(resultCount, resultCountWithinChapter, resultText,
    chapterTitle, query, pageSize, chapterIndex, pageIndex, queryIndexInResult, queryIndexInChapter, isRegex)
internal fun SearchResult.contentMatch(index: Int) = ContentSearchMatch("incoming-$index", resultCount, resultCountWithinChapter,
    resultText, chapterTitle, query, pageSize, chapterIndex, pageIndex, queryIndexInResult, queryIndexInChapter, isRegex)

@Composable internal fun ContentSearchRoute(model: ContentSearchViewModel, canHandle: () -> Boolean,
    deliver: (ReaderContentSearchDelivery) -> Unit, close: () -> Unit, modifier: Modifier = Modifier,
    eInk: Boolean = false, bottomColor: Color = MaterialTheme.colorScheme.surface,
    bottomForeground: Color = MaterialTheme.colorScheme.onSurface) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready by rememberUpdatedState(canHandle); val host by rememberUpdatedState(deliver); val finish by rememberUpdatedState(close)
    var requested by remember { mutableStateOf(false) }
    val requestFinish = { if (!requested) { requested = true; finish() } }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.refreshOptions()
            model.state.collect { value ->
                if (value.loading || !ready()) return@collect
                val key = value.pendingResult
                if (key == null) { if (value.finished) requestFinish(); return@collect }
                val snapshot = model.prepareResult(key)
                val prepared = withContext(Dispatchers.Default) {
                    ReaderContentSearchDelivery(snapshot.selected.readerResult(), snapshot.results.map { it.readerResult() }, snapshot.index)
                }
                currentCoroutineContext().ensureActive()
                if (ready() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && model.state.value.pendingResult == key && model.consumeResult(key)) {
                    host(prepared); requestFinish()
                }
            }
        }
    }
    BackHandler { requestFinish() }
    ContentSearchScreen(state, ContentSearchActions(requestFinish, model::query, model::submit, model::stopSearch,
        model::choose, model::replace, model::regex, model::retry, model::position), modifier, eInk, bottomColor, bottomForeground)
}
