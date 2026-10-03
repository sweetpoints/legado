package io.legado.app.ui.book.toc

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*

class TocHostScreenTest {
    @get:Rule val compose = createComposeRule()
    private val session = mutableStateOf(TocHostSessionState(ready = true))
    private val state = mutableStateOf(TocHostState(loaded = true, localText = true))
    private fun show(actions: TocHostActions = TocHostActions()) {
        compose.setContent { LegadoComposeTheme { TocHostScreen(session.value, state.value, actions) { Text("Page $it") } } }
    }
    @Test fun chapterChecksAndLocalTextActionsBecomeBookmarkExportsThenHighlightLogOnly() {
        show(); compose.runOnIdle { session.value = session.value.copy(menuOpen = true) }
        compose.onNodeWithTag("toc-host-expanded").assertExists()
        compose.onNodeWithTag("toc-host-regex").assertExists()
        compose.onNodeWithTag("toc-host-export-json").assertDoesNotExist()
        compose.runOnIdle { session.value = session.value.copy(tab = 1) }
        compose.onNodeWithTag("toc-host-export-json").assertExists()
        compose.onNodeWithTag("toc-host-export-markdown").assertExists()
        compose.onNodeWithTag("toc-host-reverse").assertDoesNotExist()
        compose.runOnIdle { session.value = session.value.copy(tab = 2) }
        compose.onNodeWithTag("toc-host-log").assertExists()
        compose.onNodeWithTag("toc-host-export-json").assertDoesNotExist()
    }
    @Test fun expansionActionClosesMenuAndCallsExactlyOnce() {
        var calls = 0
        show(TocHostActions(menu = { session.value = session.value.copy(menuOpen = it) }, expanded = { calls++ }))
        compose.onNodeWithTag("toc-host-menu").performClick()
        compose.onNodeWithTag("toc-host-expanded").performClick()
        compose.onNodeWithTag("toc-host-expanded").assertDoesNotExist()
        assertEquals(1, calls)
    }
    @Test fun searchKeepsFullTextAndCursorAndHidesTabsUntilClose() {
        var closes = 0
        show(TocHostActions(search = { session.value = session.value.copy(searchOpen = it); if (!it) closes++ },
            query = { text, start, end -> session.value = session.value.copy(query = text, selectionStart = start, selectionEnd = end) }))
        compose.onNodeWithTag("toc-host-search").performClick()
        compose.onNodeWithTag("toc-host-query").performTextReplacement("Exact query")
        compose.onNodeWithTag("toc-host-query").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 6))
        compose.onNodeWithTag("toc-host-tab-0").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Exact query", session.value.query); assertEquals(2, session.value.selectionStart); assertEquals(6, session.value.selectionEnd) }
        compose.onNodeWithTag("toc-host-close-search").performClick()
        compose.onNodeWithTag("toc-host-tab-0").assertExists(); assertEquals(1, closes)
    }
    @Test fun unloadedStateDisablesSearchAndMenuAndMissingBookUsesLocalizedResource() {
        state.value = TocHostState(noBook = true)
        show(); compose.onNodeWithTag("toc-host-search").assertIsNotEnabled()
        compose.onNodeWithTag("toc-host-menu").assertIsNotEnabled()
        compose.onNodeWithTag("toc-host-error").assertTextEquals(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(io.legado.app.R.string.no_book))
    }
    @Test fun pagerSwipeReportsSelectedTab() {
        var selected = -1
        show(TocHostActions(tab = { selected = it; session.value = session.value.copy(tab = it) }))
        compose.onNodeWithTag("toc-host-pager").performTouchInput { swipeLeft() }
        compose.waitUntil { selected == 1 }
    }
    @Test fun pageLifecycleCapsHiddenPageAtStartedAndDisposalIsFinal() {
        compose.runOnIdle {
            val owner = TocPageLifecycleOwner()
            owner.update(Lifecycle.State.RESUMED, false); assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)
            owner.update(Lifecycle.State.RESUMED, true); assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
            owner.update(Lifecycle.State.CREATED, true); assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)
            owner.dispose(); assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
            owner.update(Lifecycle.State.RESUMED, true); assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        }
    }
    @Test fun hiddenPageScrollRequestRemainsUnconsumedUntilItBecomesVisible() {
        var active by mutableStateOf(false); var count = 0
        val rows = (0..9).map { TocBookmarkRow(it.toLong(), it, "Chapter $it", "Original", "Note") }
        compose.setContent { LegadoComposeTheme { TocBookmarksScreen(TocBookmarksState(loaded = true, rows = rows, scrollRequest = 3, scrollTarget = 8),
            TocBookmarksActions(scrolled = { count++ }), active) } }
        compose.waitForIdle(); assertEquals(0, count)
        compose.runOnIdle { active = true }; compose.waitUntil { count == 1 }
        compose.waitForIdle(); assertEquals(1, count)
    }
    @Test fun hiddenResumedRouteDoesNotConsumeNativeTicketAndVisibleRouteDeliversOnlyOnce() {
        val row = Bookmark(time = 10, bookName = "Owned", chapterName = "Chapter")
        val repo = object : TocBookmarksRepository {
            override suspend fun checkpoint(session: String): TocBookmarksCheckpoint? = null
            override suspend fun checkpoint(session: String, value: TocBookmarksCheckpoint) {}
            override suspend fun release(session: String) {}
            override fun observe(parameters: TocBookmarksParameters) = flowOf(listOf(TocBookmarkRow(10, 0, "Chapter", "", "")))
            override suspend fun resolve(parameters: TocBookmarksParameters, id: Long) = row
        }
        lateinit var model: TocBookmarksViewModel
        var active by mutableStateOf(false); var deliveries = 0
        val owner = TocPageLifecycleOwner()
        compose.runOnIdle { owner.update(Lifecycle.State.RESUMED, true); model = TocBookmarksViewModel(repo, SavedStateHandle()); model.bind(TocBookmarksParameters("Owned")) }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            LegadoComposeTheme { TocBookmarksRoute(model, { true }, { _, _, _ -> deliveries++ }, active) }
        } }
        try {
            compose.waitUntil { model.state.value.loaded }
            compose.runOnIdle { model.open(10, false) }; compose.waitForIdle()
            assertNotNull(model.state.value.open); assertEquals(0, deliveries)
            compose.runOnIdle { active = true }; compose.waitUntil { deliveries == 1 }
            compose.runOnIdle { active = false }; compose.runOnIdle { active = true }
            compose.waitForIdle(); assertNull(model.state.value.open); assertEquals(1, deliveries)
        } finally { compose.runOnIdle { model.stop(); owner.dispose() } }
    }
}
