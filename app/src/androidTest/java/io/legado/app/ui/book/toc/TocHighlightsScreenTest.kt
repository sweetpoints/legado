package io.legado.app.ui.book.toc

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class TocHighlightsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state =
        mutableStateOf(
            TocHighlightsState(
                loaded = true,
                rows =
                    listOf(
                        TocHighlightRow(1, 1, "Chapter", "Original", "Note", 0xff123456.toInt()),
                        TocHighlightRow(2, 2, "Second", "", "", 0),
                    ),
            )
        )

    private fun show(actions: TocHighlightsActions = TocHighlightsActions()) {
        compose.setContent { LegadoComposeTheme { TocHighlightsScreen(state.value, actions) } }
    }

    @Test
    fun cardFieldsAndEmptyOriginalOrNoteVisibilityKeepOldPresentation() {
        show()
        compose
            .onNodeWithTag("toc-highlights-chapter-1", useUnmergedTree = true)
            .assertTextEquals("Chapter")
        compose
            .onNodeWithTag("toc-highlights-original-1", useUnmergedTree = true)
            .assertTextEquals("Original")
        compose
            .onNodeWithTag("toc-highlights-note-1", useUnmergedTree = true)
            .assertTextEquals("Note")
        compose
            .onNodeWithTag("toc-highlights-original-2", useUnmergedTree = true)
            .assertDoesNotExist()
        compose.onNodeWithTag("toc-highlights-note-2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun colorSwatchUsesProjectedArgbAndKeepsVisibleOriginalWidth() {
        show()
        val node = compose.onNodeWithTag("toc-highlights-color-1", useUnmergedTree = true)
        node.assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(4f))
        val image = node.captureToImage()
        val pixels = image.toPixelMap()
        assertEquals(Color(0xff123456.toInt()), pixels[image.width / 2, image.height / 2])
    }

    @Test
    fun stableRowIdSeparatesReadAndLongPressEditAndPendingDisablesAnotherTap() {
        val opened = mutableListOf<Pair<Long, Boolean>>()
        show(TocHighlightsActions(open = { id, edit -> opened += id to edit }))
        compose.onNodeWithTag("toc-highlights-row-1").performClick()
        compose.onNodeWithTag("toc-highlights-row-2").performTouchInput { longClick() }
        assertEquals(listOf(1L to false, 2L to true), opened)
        compose.runOnIdle { state.value = state.value.copy(open = TocHighlightOpen(1, false)) }
        compose.onNodeWithTag("toc-highlights-row-1").assertIsNotEnabled()
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
        show(TocHighlightsActions(scrolled = { acknowledged += it }))
        assertTrue(acknowledged.isEmpty())
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    loaded = true,
                    rows =
                        (1..90).map { TocHighlightRow(it.toLong(), it, "Chapter $it", "", "", 0) },
                )
        }
        compose.onNodeWithTag("toc-highlights-row-61").assertIsDisplayed()
        assertEquals(listOf(8L), acknowledged)
    }

    @Test
    fun fastScrollAccessibilityProgressActuallyRevealsLastHighlight() {
        state.value =
            state.value.copy(
                rows = (1..100).map { TocHighlightRow(it.toLong(), it, "Chapter $it", "", "", 0) }
            )
        show()
        compose.onNodeWithTag("toc-highlights-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(1f)
        }
        compose.onNodeWithTag("toc-highlights-row-100").assertIsDisplayed()
    }

    @Test
    fun queryErrorOffersRetryWithoutRemovingExistingRows() {
        state.value = state.value.copy(error = "Query failed")
        var retries = 0
        show(TocHighlightsActions(retry = { retries++ }))
        compose.onNodeWithTag("toc-highlights-error").assertTextEquals("Query failed")
        compose.onNodeWithTag("toc-highlights-retry").performClick()
        compose.onNodeWithTag("toc-highlights-row-1").assertExists()
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
            object : TocHighlightsRepository {
                override suspend fun release(session: String) {}

                override suspend fun checkpoint(session: String): TocHighlightsCheckpoint? = null

                override suspend fun checkpoint(session: String, value: TocHighlightsCheckpoint) {}

                override fun observe(parameters: TocHighlightsParameters) =
                    MutableStateFlow(listOf(TocHighlightRow(7, 3, "Chapter", "", "", 0)))

                override suspend fun resolve(parameters: TocHighlightsParameters, id: Long) =
                    TocHighlightTarget(
                        BookHighlight(
                            time = 7,
                            bookUrl = "book",
                            chapterIndex = 3,
                            chapterPos = 99,
                            chapterName = "Chapter",
                            bookText = "Large".repeat(50000),
                            note = "Note",
                        ),
                        3,
                    )
            }
        lateinit var owner: Owner
        lateinit var model: TocHighlightsViewModel
        val opened = mutableListOf<TocHighlightTarget>()
        compose.runOnIdle {
            owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
            model = TocHighlightsViewModel(repo, SavedStateHandle())
            model.bind(TocHighlightsParameters("book"))
        }
        try {
            compose.setContent {
                LegadoComposeTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        TocHighlightsRoute(
                            model,
                            { true },
                            { row, edit ->
                                assertNull(model.state.value.open)
                                assertTrue(edit)
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
            assertEquals(99, opened.single().highlight.chapterPos)
            assertEquals(250000, opened.single().highlight.bookText.length)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitForIdle()
            assertEquals(1, opened.size)
        } finally {
            compose.runOnIdle { model.stop() }
        }
    }
}
