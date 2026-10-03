package io.legado.app.ui.book.toc

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
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

class TocBookmarksScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state =
        mutableStateOf(
            TocBookmarksState(
                loaded = true,
                rows =
                    listOf(
                        TocBookmarkRow(1, 1, "Chapter", "Original", "Note"),
                        TocBookmarkRow(2, 2, "Second", "", ""),
                    ),
            )
        )

    private fun show(actions: TocBookmarksActions = TocBookmarksActions()) {
        compose.setContent { LegadoComposeTheme { TocBookmarksScreen(state.value, actions) } }
    }

    @Test
    fun cardFieldsAndEmptyOriginalOrNoteVisibilityKeepOldPresentation() {
        show()
        compose.onNodeWithTag("toc-bookmarks-chapter-1").assertTextEquals("Chapter")
        compose.onNodeWithTag("toc-bookmarks-original-1").assertTextEquals("Original")
        compose.onNodeWithTag("toc-bookmarks-content-1").assertTextEquals("Note")
        compose.onNodeWithTag("toc-bookmarks-original-2").assertDoesNotExist()
        compose.onNodeWithTag("toc-bookmarks-content-2").assertDoesNotExist()
    }

    @Test
    fun stableRowIdSeparatesReadAndLongPressEditAndPendingDisablesAnotherTap() {
        val opened = mutableListOf<Pair<Long, Boolean>>()
        show(TocBookmarksActions(open = { id, edit -> opened += id to edit }))
        compose.onNodeWithTag("toc-bookmarks-row-1").performClick()
        compose.onNodeWithTag("toc-bookmarks-row-2").performTouchInput { longClick() }
        assertEquals(listOf(1L to false, 2L to true), opened)
        compose.runOnIdle { state.value = state.value.copy(open = TocBookmarkOpen(1, false)) }
        compose.onNodeWithTag("toc-bookmarks-row-1").assertIsNotEnabled()
    }

    @Test
    fun scrollRequestWaitsForDataReachesActualTargetAndAcknowledgesExactToken() {
        state.value =
            state.value.copy(
                loaded = false,
                rows = emptyList(),
                scrollTarget = 60,
                scrollRequest = 8,
            )
        val acknowledged = mutableListOf<Long>()
        show(TocBookmarksActions(scrolled = { acknowledged += it }))
        assertTrue(acknowledged.isEmpty())
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    loaded = true,
                    rows = (1..90).map { TocBookmarkRow(it.toLong(), it, "Chapter $it", "", "") },
                )
        }
        compose.onNodeWithTag("toc-bookmarks-row-61").assertIsDisplayed()
        assertEquals(listOf(8L), acknowledged)
    }

    @Test
    fun fastScrollAccessibilityProgressActuallyRevealsLastBookmark() {
        state.value =
            state.value.copy(
                rows = (1..100).map { TocBookmarkRow(it.toLong(), it, "Chapter $it", "", "") }
            )
        show()
        compose.onNodeWithTag("toc-bookmarks-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(1f)
        }
        compose.onNodeWithTag("toc-bookmarks-row-100").assertIsDisplayed()
    }

    @Test
    fun queryErrorOffersRetryWithoutRemovingExistingRows() {
        state.value = state.value.copy(error = "Query failed")
        var retries = 0
        show(TocBookmarksActions(retry = { retries++ }))
        compose.onNodeWithTag("toc-bookmarks-error").assertTextEquals("Query failed")
        compose.onNodeWithTag("toc-bookmarks-retry").performClick()
        compose.onNodeWithTag("toc-bookmarks-row-1").assertExists()
        assertEquals(1, retries)
    }

    @Test
    fun resumedReaderCallbackConsumesSmallTicketAndUsesLatestFullMetadata() {
        class Owner : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle
                get() = registry
        }
        val repo =
            object : TocBookmarksRepository {
                override suspend fun release(session: String) {}

                override suspend fun checkpoint(session: String): TocBookmarksCheckpoint? = null

                override suspend fun checkpoint(session: String, value: TocBookmarksCheckpoint) {}

                override fun observe(parameters: TocBookmarksParameters) =
                    MutableStateFlow(listOf(TocBookmarkRow(7, 3, "Chapter", "", "")))

                override suspend fun resolve(parameters: TocBookmarksParameters, id: Long) =
                    Bookmark(7, "Book", "Author", 3, 99, "Chapter", "Large".repeat(50000), "Note")
            }
        lateinit var owner: Owner
        lateinit var model: TocBookmarksViewModel
        val opened = mutableListOf<Bookmark>()
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = TocBookmarksViewModel(repo, SavedStateHandle())
            model.bind(TocBookmarksParameters("Book", "Author"))
        }
        try {
            compose.setContent {
                LegadoComposeTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        TocBookmarksRoute(
                            model,
                            { true },
                            { row, edit, position ->
                                assertNull(model.state.value.open)
                                assertTrue(edit)
                                assertEquals(0, position)
                                opened += row
                            },
                        )
                    }
                }
            }
            compose.waitUntil { model.state.value.loaded }
            compose.runOnIdle { model.open(7, true) }
            compose.waitForIdle()
            assertTrue(opened.isEmpty())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { opened.size == 1 }
            assertEquals(99, opened.single().chapterPos)
            assertEquals(250000, opened.single().bookText.length)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle()
            assertEquals(1, opened.size)
        } finally {
            compose.runOnIdle { model.stop() }
        }
    }
}
