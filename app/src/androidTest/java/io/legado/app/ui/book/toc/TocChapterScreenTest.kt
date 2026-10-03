package io.legado.app.ui.book.toc

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
import io.legado.app.model.book.toc.TocListItem
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class TocChapterScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(TocChapterState(loaded = true, currentInfo = "Current(2/3)", rows = listOf(
        TocChapterRow("volume:0", 0, 0, "Volume", volume = true, canToggle = true, chapterCount = 2),
        TocChapterRow("chapter:1", 1, 1, "Current", current = true, words = "10", locked = true, depth = 1),
        TocChapterRow("chapter:2", 2, 2, "Uncached", cached = false, tag = "Today"))))
    private fun show(actions: TocChapterActions = TocChapterActions()) { compose.setContent { LegadoComposeTheme { TocChapterScreen(state.value, actions) } } }
    @Test fun titleMetadataVipCacheCurrentAndVolumeFieldsKeepOldPresentation() {
        show(); compose.onNodeWithTag("toc-chapter-title-chapter:1", useUnmergedTree = true).assertTextEquals("Current")
        compose.onNodeWithTag("toc-chapter-words-chapter:1", useUnmergedTree = true).assertTextEquals("10")
        compose.onNodeWithTag("toc-chapter-locked-chapter:1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("toc-chapter-current-chapter:1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("toc-chapter-cloud-chapter:2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("toc-chapter-summary-chapter:2", useUnmergedTree = true).assertTextEquals("Today")
        compose.onNodeWithTag("toc-chapter-current-info").assertTextEquals("Current(2/3)")
    }
    @Test fun bodyReadLongPressAndArrowToggleUseStableKeysAndVisibleAnchorSeparately() {
        val reads = mutableListOf<Pair<String, Boolean>>(); val toggles = mutableListOf<Pair<String, String?>>()
        show(TocChapterActions(open = { key, long, _ -> reads += key to long }, toggle = { key, anchor -> toggles += key to anchor }))
        compose.onNodeWithTag("toc-chapter-row-chapter:1").performClick()
        compose.onNodeWithTag("toc-chapter-row-chapter:2").performTouchInput { longClick() }
        compose.onNodeWithTag("toc-chapter-toggle-volume:0").performClick()
        assertEquals(listOf("chapter:1" to false, "chapter:2" to true), reads); assertEquals(listOf("volume:0" to "volume:0"), toggles)
    }
    @Test fun pendingSmallTicketDisablesRowAndToggleUntilDelivery() {
        state.value = state.value.copy(open = TocChapterOpen("chapter:1", false, "owner")); show()
        compose.onNodeWithTag("toc-chapter-row-chapter:1").assertIsNotEnabled()
        compose.onNodeWithTag("toc-chapter-toggle-volume:0").assertIsNotEnabled()
    }
    @Test fun loadedScrollRequestActuallyReachesTargetAndDoesNotAckBeforeRowsArrive() {
        val acks = mutableListOf<Long>(); state.value = state.value.copy(loaded = false, rows = emptyList(), scrollRequest = 12, scrollTarget = 60)
        show(TocChapterActions(scrolled = { acks += it })); assertTrue(acks.isEmpty())
        compose.runOnIdle { state.value = state.value.copy(loaded = true, rows = (0..90).map { TocChapterRow("chapter:$it", it, it, "Chapter $it") }) }
        compose.onNodeWithTag("toc-chapter-row-chapter:60").assertIsDisplayed(); assertEquals(listOf(12L), acks)
    }
    @Test fun fastScrollerRevealsLastActualChapterAndFooterControlsRemainClickable() {
        state.value = state.value.copy(rows = (0..99).map { TocChapterRow("chapter:$it", it, it, "Chapter $it") })
        var current = 0; var top = 0; var bottom = 0; show(TocChapterActions(current = { current++ }, top = { top++ }, bottom = { bottom++ }))
        compose.onNodeWithTag("toc-chapter-fast-scroll").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithTag("toc-chapter-row-chapter:99").assertIsDisplayed()
        compose.onNodeWithTag("toc-chapter-current-info").performClick(); compose.onNodeWithTag("toc-chapter-top").performClick(); compose.onNodeWithTag("toc-chapter-bottom").performClick()
        assertEquals(listOf(1, 1, 1), listOf(current, top, bottom))
    }
    @Test fun pdfRowsKeepRawPageSummaryAndSeparateParentArrowFromReadingEntry() {
        state.value = state.value.copy(pdf = true, rows = listOf(TocChapterRow("pdf:0", 0, null, "Parent", canToggle = true, volume = true), TocChapterRow("pdf:1", 1, null, "Page", pdfPage = 27, depth = 1)))
        var read: String? = null; var toggle: String? = null
        show(TocChapterActions(open = { key, _, _ -> read = key }, toggle = { key, _ -> toggle = key }))
        compose.onNodeWithTag("toc-chapter-toggle-pdf:0").assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(48f)).performClick()
        compose.onNodeWithTag("toc-chapter-row-pdf:1").performClick()
        assertEquals("pdf:0", toggle); assertEquals("pdf:1", read)
        compose.onNodeWithTag("toc-chapter-summary-pdf:1", useUnmergedTree = true).assertTextContains("28")
        compose.onNodeWithTag("toc-chapter-cloud-pdf:1", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun pausedRouteWaitsForResumeConsumesExactTicketBeforeNativeCallbackAndDoesNotReplay() {
        class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
        val repo = object : TocChapterRepository {
            override suspend fun checkpoint(session: String): TocChapterCheckpoint? = null
            override suspend fun checkpoint(session: String, value: TocChapterCheckpoint) {}
            override suspend fun release(session: String) {}
            override suspend fun load(parameters: TocChapterParameters) = TocChapterSnapshot(listOf(BookChapter(url = "one", index = 1, title = "One")), null, emptyList())
            override suspend fun search(parameters: TocChapterParameters, query: String) = listOf(1)
            override fun titles(book: Book, items: List<TocListItem>) = flowOf("chapter:1" to "One")
            override suspend fun title(book: Book, item: TocListItem) = "Full title"
            override suspend fun cache(book: Book) = TocChapterCache()
            override suspend fun resolve(book: Book, chapterUrl: String) = TocChapterNavigation(4, true)
        }
        lateinit var owner: Owner; lateinit var model: TocChapterViewModel; val deliveries = mutableListOf<TocChapterDelivery>()
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }; model = TocChapterViewModel(repo, SavedStateHandle()); model.bind(TocChapterParameters(Book(bookUrl = "book", origin = "remote", totalChapterNum = 5))) }
        try {
            compose.setContent { LegadoComposeTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) { TocChapterRoute(model, { true }, { assertNull(model.state.value.open); deliveries += it }) } } }
            compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { model.request("chapter:1") }; compose.waitForIdle(); assertTrue(deliveries.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { deliveries.size == 1 }; assertEquals(4, deliveries.single().navigation!!.index)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitForIdle(); assertEquals(1, deliveries.size)
        } finally { compose.runOnIdle { model.stop() } }
    }
}
