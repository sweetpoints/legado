package io.legado.app.ui.book.bookmark

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class AllBookmarksScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(AllBookmarksState(loaded = true, rows = listOf(
        AllBookmarksRow(1, "A", "One", "First chapter", "Original", "Note"),
        AllBookmarksRow(2, "A", "Two", "Second chapter", "", ""))))
    private fun show(actions: AllBookmarksActions = AllBookmarksActions()) {
        compose.setContent { LegadoComposeTheme { AllBookmarksScreen(state.value, actions) } }
    }
    @Test fun groupsDistinguishSameTitleAuthorsAndEmptyOriginalAndNoteAreHidden() {
        show(); compose.onNodeWithTag("all-bookmarks-header-1").assertTextEquals("A(One)")
        compose.onNodeWithTag("all-bookmarks-header-2").assertTextEquals("A(Two)")
        compose.onNodeWithTag("all-bookmarks-original-1").assertTextEquals("Original"); compose.onNodeWithTag("all-bookmarks-content-1").assertTextEquals("Note")
        compose.onNodeWithTag("all-bookmarks-original-2").assertDoesNotExist(); compose.onNodeWithTag("all-bookmarks-content-2").assertDoesNotExist()
    }
    @Test fun clickAndLongPressUseStablePrimaryKeyAndDifferentNavigationMode() {
        val opened = mutableListOf<Pair<Long, Boolean>>(); show(AllBookmarksActions(open = { id, edit -> opened += id to edit }))
        compose.onNodeWithTag("all-bookmarks-row-1").performClick()
        compose.onNodeWithTag("all-bookmarks-row-2").performTouchInput { longClick() }
        assertEquals(listOf(1L to false, 2L to true), opened)
    }
    @Test fun exportMenuPreservesJsonAndMarkdownAndBusyDisablesMutation() {
        val formats = mutableListOf<Boolean>(); show(AllBookmarksActions(export = { formats += it }))
        compose.onNodeWithTag("all-bookmarks-menu").performClick(); compose.onNodeWithTag("all-bookmarks-export-json").performClick()
        compose.onNodeWithTag("all-bookmarks-menu").performClick(); compose.onNodeWithTag("all-bookmarks-export-md").performClick()
        assertEquals(listOf(false, true), formats)
        compose.runOnIdle { state.value = state.value.copy(exporting = true) }
        compose.onNodeWithTag("all-bookmarks-menu").assertIsNotEnabled(); compose.onNodeWithTag("all-bookmarks-row-1").assertIsNotEnabled()
    }
    @Test fun savedScrollWaitsForDataAndStickyGroupHeaderRemainsVisible() {
        state.value = state.value.copy(loaded = false, rows = emptyList(), scroll = 61)
        show(); compose.runOnIdle { state.value = state.value.copy(loaded = true, rows = (1..90).map { AllBookmarksRow(it.toLong(), "Book", "Author", "Chapter $it", "", "") }) }
        compose.onNodeWithTag("all-bookmarks-row-61").assertIsDisplayed()
        compose.onNodeWithTag("all-bookmarks-header-1").assertIsDisplayed()
        compose.onNodeWithTag("all-bookmarks-list").performScrollToNode(hasTestTag("all-bookmarks-row-90"))
        compose.onNodeWithTag("all-bookmarks-row-90").assertIsDisplayed()
    }
    private class Fake : AllBookmarksRepository {
        val rows = MutableStateFlow(listOf(AllBookmarksRow(1, "Book", "Author", "Chapter", "", "")))
        var bookmark = Bookmark(1, "Book", "Author", 4, 17, "Chapter", "Full text", "Note")
        var gate: CompletableDeferred<Unit>? = null; var reads = 0
        override fun observe() = rows
        override suspend fun resolve(id: Long, edit: Boolean): AllBookmarksDestination {
            reads++; withContext(NonCancellable) { gate?.await() }; return AllBookmarksDestination(bookmark.copy(), null)
        }
        override suspend fun export(directory: String, markdown: Boolean) = "bookmark.md"
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    @Test fun pendingDirectoryWaitsForResumedAndAcknowledgesBeforeNativeCallback() {
        lateinit var owner: Owner; lateinit var model: AllBookmarksViewModel; val formats = mutableListOf<Boolean>()
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }; model = AllBookmarksViewModel(Fake(), SavedStateHandle()) }
        try {
            compose.setContent { LegadoComposeTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AllBookmarksRoute(model, { true }, {}, { _, _ -> }, { assertNull(model.state.value.effect); formats += it }, {})
            } } }
            compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { model.requestExport(true) }; compose.waitForIdle(); assertTrue(formats.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { formats.size == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle(); assertEquals(listOf(true), formats)
        } finally { compose.runOnIdle { model.stop() } }
    }
    @Test fun pausedLateNonCooperativeReadKeepsTicketAndResumedDeliveryUsesLatestMetadata() {
        val repo = Fake(); lateinit var owner: Owner; lateinit var model: AllBookmarksViewModel; val delivered = mutableListOf<AllBookmarksDestination>()
        compose.runOnIdle { owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }; model = AllBookmarksViewModel(repo, SavedStateHandle()) }
        try {
            compose.setContent { LegadoComposeTheme { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AllBookmarksRoute(model, { true }, {}, { value, _ -> assertNull(model.state.value.effect); delivered += value }, {}, {})
            } } }
            compose.waitUntil { model.state.value.loaded }; compose.runOnIdle { repo.gate = CompletableDeferred(); model.open(1, true) }
            compose.waitUntil { repo.reads == 1 }; compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.waitForIdle(); compose.runOnIdle { repo.bookmark = repo.bookmark.copy(chapterPos = 99); repo.gate!!.complete(Unit) }
            compose.waitForIdle(); assertTrue(delivered.isEmpty()); assertNotNull(model.state.value.effect)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { delivered.size == 1 }
            assertEquals(99, delivered.single().bookmark.chapterPos); assertEquals(2, repo.reads)
        } finally { compose.runOnIdle { model.stop() } }
    }
}
