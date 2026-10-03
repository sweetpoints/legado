package io.legado.app.ui.book.toc

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import io.legado.app.help.book.*
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CancellationException

@Composable
fun TocHostRoute(sessionModel: TocHostSessionViewModel, hostModel: TocHostViewModel,
    chapterModel: TocChapterViewModel, bookmarkModel: TocBookmarksViewModel, highlightModel: TocHighlightsViewModel,
    ready: () -> Boolean, back: () -> Unit, effect: (TocHostEffect) -> Unit,
    chapter: (TocChapterDelivery) -> Unit, bookmark: (Bookmark, Boolean, Int) -> Unit,
    highlight: (TocHighlightTarget, Boolean) -> Unit) {
    val session by sessionModel.state.collectAsStateWithLifecycle()
    val state by hostModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    val currentReady by rememberUpdatedState(ready)
    val currentEffect by rememberUpdatedState(effect)
    LaunchedEffect(session.ready, session.bookUrl) { if (session.ready) hostModel.load(session.bookUrl.orEmpty()) }
    var boundOwner by remember { mutableStateOf<String?>(null) }
    var appliedRevision by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(session.ready, state.loaded, state.chapterRevision, session.query, session.tab) {
        if (!session.ready || !state.loaded) return@LaunchedEffect
        val book = hostModel.snapshot() ?: return@LaunchedEffect
        val initial = boundOwner != book.bookUrl
        val changed = appliedRevision != state.chapterRevision
        if (initial || changed || session.tab == 0) {
            chapterModel.update(TocChapterParameters(book, session.query, state.countWords),
                resetCollapse = !initial && changed && state.resetCollapse,
                replaceAll = !initial && changed && state.replaceAll)
        }
        if (initial || session.tab == 1) bookmarkModel.bind(TocBookmarksParameters(book.name, book.author, session.query, book.durChapterIndex))
        if (initial || session.tab == 2) highlightModel.bind(TocHighlightsParameters(book.bookUrl, session.query, book.durChapterIndex,
            !book.isAudio && !book.isVideo && (book.isLocal || !book.isImage || !AppConfig.showMangaUi)))
        boundOwner = book.bookUrl; appliedRevision = state.chapterRevision
    }
    LaunchedEffect(state.pending, state.loaded, lifecycle) {
        if (!state.loaded || lifecycle != Lifecycle.State.RESUMED || !currentReady()) return@LaunchedEffect
        val pending = state.pending ?: return@LaunchedEffect
        val accepted = hostModel.delivered(pending.nonce) ?: return@LaunchedEffect
        try { currentEffect(accepted) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { hostModel.failed(error.localizedMessage ?: "Error") }
    }
    TocHostScreen(session, state, TocHostActions(back, sessionModel::tab, { open ->
        if (!open) sessionModel.query("")
        sessionModel.search(open)
    }, sessionModel::query, sessionModel::menu, hostModel::reverse, hostModel::expanded, hostModel::useReplace,
        hostModel::countWords, hostModel::split, hostModel::effect, { sessionModel.retry(); hostModel.retry() })) { page ->
        val active = session.tab == page && session.ready && state.loaded
        val pageOwner = rememberTocPageLifecycle(owner, active)
        CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) {
            when (page) {
                0 -> TocChapterRoute(chapterModel, { active && currentReady() }, chapter, active)
                1 -> TocBookmarksRoute(bookmarkModel, { active && currentReady() }, bookmark, active)
                else -> TocHighlightsRoute(highlightModel, { active && currentReady() }, highlight, active)
            }
        }
    }
}

/** Pager pages stay STARTED for Room updates, while only the visible page can deliver native effects. */
internal class TocPageLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun update(parent: Lifecycle.State, active: Boolean) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = if (parent == Lifecycle.State.DESTROYED) parent else
            minOf(parent, if (active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED)
    }
    fun dispose() { registry.currentState = Lifecycle.State.DESTROYED }
}

@Composable private fun rememberTocPageLifecycle(parent: LifecycleOwner, active: Boolean): TocPageLifecycleOwner {
    val owner = remember(parent) { TocPageLifecycleOwner() }
    val currentActive by rememberUpdatedState(active)
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> owner.update(parent.lifecycle.currentState, currentActive) }
        parent.lifecycle.addObserver(observer)
        owner.update(parent.lifecycle.currentState, currentActive)
        onDispose { parent.lifecycle.removeObserver(observer); owner.dispose() }
    }
    SideEffect { owner.update(parent.lifecycle.currentState, active) }
    return owner
}
