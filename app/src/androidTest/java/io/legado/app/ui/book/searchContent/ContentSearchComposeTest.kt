package io.legado.app.ui.book.searchContent

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.model.book.ContentSearchMatch
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class ContentSearchComposeTest {
    @get:Rule val compose = createComposeRule()
    private val owners = mutableListOf<ViewModelStore>()

    @After
    fun clear() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.forEach { it.clear() } }
    }

    private fun match(
        id: String = "match",
        text: String = "Long needle snippet ".repeat(12),
        chapter: Int = 3,
    ) =
        ContentSearchMatch(
            id,
            resultText = text,
            chapterTitle = "Chapter",
            query = "needle",
            chapterIndex = chapter,
        )

    private fun actions() = ContentSearchActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun layouts(id: String = "match"): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("content-search-text-$id", true).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult
        ) {
            it(result)
        }
        return result.single()
    }

    @Test
    fun resultCardUsesAvailableWidthMultilineFourteenSpAndCurrentChapterBoldWithClickablePayload() {
        val selected = mutableListOf<String>()
        val match = match()
        compose.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(
                    ContentSearchState(
                        loading = false,
                        focusInput = false,
                        results = listOf(match),
                        currentChapter = 3,
                    ),
                    actions().copy(choose = { selected += it }),
                )
            }
        }
        compose
            .onNodeWithTag("content-search-result-match")
            .assertHasClickAction()
            .assertWidthIsAtLeast(280.dp)
            .assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("content-search-text-match", true).assertWidthIsAtLeast(250.dp)
        val layout = layouts()
        assertTrue(layout.lineCount > 2)
        assertEquals(14f, layout.layoutInput.style.fontSize.value, 0f)
        assertEquals(FontWeight.Bold, layout.layoutInput.style.fontWeight)
        compose.onNodeWithTag("content-search-result-match").performClick()
        assertEquals(listOf("match"), selected)
    }

    @Test
    fun eInkHighlightUnderlinesTitleAndMatchWhileOtherChaptersRetainNormalWeight() {
        compose.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(
                    ContentSearchState(
                        loading = false,
                        focusInput = false,
                        results = listOf(match()),
                        currentChapter = 8,
                    ),
                    actions(),
                    eInk = true,
                )
            }
        }
        val layout = layouts()
        assertEquals(FontWeight.Normal, layout.layoutInput.style.fontWeight)
        val spans = layout.layoutInput.text.spanStyles
        assertEquals(2, spans.size)
        assertTrue(spans.all { it.item.textDecoration == TextDecoration.Underline })
        assertEquals(
            "Chapter",
            layout.layoutInput.text.substring(spans.first().start, spans.first().end),
        )
        assertEquals(
            "needle",
            layout.layoutInput.text.substring(spans.last().start, spans.last().end),
        )
    }

    @Test
    fun toolbarInputSubmitFlagsAndStopRemainReachableWithCheckedStateAndNoImplicitRestart() {
        var state by
            mutableStateOf(ContentSearchState(loading = false, focusInput = false, running = true))
        var submitted = 0
        var stopped = 0
        compose.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(
                    state,
                    actions()
                        .copy(
                            query = { state = state.copy(query = it) },
                            submit = { submitted++ },
                            stop = { stopped++ },
                            replace = {
                                state = state.copy(options = state.options.copy(replace = it))
                            },
                            regex = {
                                state = state.copy(options = state.options.copy(regex = it))
                            },
                        ),
                )
            }
        }
        compose.onNodeWithTag("content-search-query").performTextReplacement(" needle ")
        compose.onNodeWithTag("content-search-query").assertTextContains(" needle ")
        compose.onNodeWithTag("content-search-submit").assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(1, submitted)
        compose.onNodeWithTag("content-search-menu").performClick()
        compose.onNodeWithTag("content-search-replace").performClick()
        compose.onNodeWithTag("content-search-menu").performClick()
        compose.onNodeWithTag("content-search-regex").performClick()
        assertEquals(ContentSearchOptions(true, true), state.options)
        assertEquals(1, submitted)
        compose.onNodeWithTag("content-search-stop").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun completedEmptyPlaceholderCannotDeliverAndCancelledPartialListRetainsCount() {
        var state by
            mutableStateOf(
                ContentSearchState(loading = false, focusInput = false, completed = true)
            )
        val selected = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(state, actions().copy(choose = { selected += it }))
            }
        }
        compose.onNodeWithTag("content-search-empty").assertExists().assertHasNoClickAction()
        assertTrue(selected.isEmpty())
        compose.runOnIdle { state = state.copy(completed = false, results = listOf(match())) }
        compose.onNodeWithTag("content-search-empty").assertDoesNotExist()
        compose.onNodeWithTag("content-search-count").assertTextEquals(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.search_content_size) + ": 1"
        )
        compose.onNodeWithTag("content-search-result-match").performClick()
        assertEquals(listOf("match"), selected)
    }

    @Test
    fun currentPositionRestoresAndBottomTopButtonsScrollLargeResultList() {
        val rows = (0..30).map { match("row-$it", "needle row $it") }
        val positions = mutableListOf<Int>()
        var closed = 0
        compose.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(
                    ContentSearchState(
                        loading = false,
                        focusInput = false,
                        results = rows,
                        position = 15,
                    ),
                    actions().copy(position = { positions += it }, close = { closed++ }),
                )
            }
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            positions.contains(15)
        }
        compose.onNodeWithTag("content-search-result-row-15").assertIsDisplayed()
        compose.onNodeWithTag("content-search-bottom").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            positions.any { it >= 20 }
        }
        compose.onNodeWithTag("content-search-result-row-30").assertIsDisplayed()
        compose.onNodeWithTag("content-search-top").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            positions.lastOrNull() == 0
        }
        compose.onNodeWithTag("content-search-result-row-0").assertIsDisplayed()
        compose.onNodeWithTag("content-search-back").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun resultPreparedWhilePausedDeliversOnlyAfterResumeAndNeverReplaysAfterCompositionRestore() {
        val repository = Fake(ContentSearchSession("book", results = listOf(match())))
        val model = vm(repository)
        val lifecycle = owner()
        val restoration = StateRestorationTester(compose)
        val received = mutableListOf<ReaderContentSearchDelivery>()
        var closed = 0
        restoration.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                LegadoComposeTheme {
                    ContentSearchRoute(model, { true }, { received += it }, { closed++ })
                }
            }
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            !model.state.value.loading
        }
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithTag("content-search-result-match").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            model.state.value.pendingResult != null
        }
        assertTrue(received.isEmpty())
        restoration.emulateSavedInstanceStateRestore()
        assertTrue(received.isEmpty())
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            received.size == 1 && closed == 1
        }
        assertEquals(0, received.single().index)
        assertEquals("needle", received.single().selected.query)
        assertEquals(received.single().selected, received.single().results.single())
        compose.runOnIdle {
            lifecycle.registry.currentState = Lifecycle.State.STARTED
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals(1, received.size)
        // A recreated host may close itself again, but the reader result is consumed only once.
    }

    @Test
    fun hostReadinessBlocksDeliveryUntilNextResume() {
        val repository = Fake(ContentSearchSession("book", results = listOf(match())))
        val model = vm(repository)
        val lifecycle = owner()
        var ready = false
        val received = mutableListOf<ReaderContentSearchDelivery>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                LegadoComposeTheme {
                    ContentSearchRoute(model, { ready }, { received += it }, {})
                }
            }
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            !model.state.value.loading
        }
        compose.onNodeWithTag("content-search-result-match").performClick()
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            model.state.value.pendingResult != null
        }
        assertTrue(received.isEmpty())
        compose.runOnIdle {
            ready = true
            lifecycle.registry.currentState = Lifecycle.State.STARTED
            lifecycle.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            received.size == 1
        }
        assertEquals("needle", received.single().selected.query)
    }

    @Test
    fun inputCursorAndOpenMenuRestoreWithoutKeepingTheQueryInComposeSavedState() {
        val restoration = StateRestorationTester(compose)
        var state by
            mutableStateOf(
                ContentSearchState(loading = false, focusInput = false, query = "needle draft")
            )
        restoration.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(
                    state,
                    actions().copy(query = { state = state.copy(query = it) }),
                )
            }
        }
        compose
            .onNodeWithTag("content-search-query")
            .performTextInputSelection(androidx.compose.ui.text.TextRange(2, 5))
        compose.onNodeWithTag("content-search-menu").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("content-search-regex").assertExists()
        compose
            .onNodeWithTag("content-search-query")
            .assertTextContains("needle draft")
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange,
                    androidx.compose.ui.text.TextRange(2, 5),
                )
            )
    }

    @Test
    fun loadingFailureShowsRetryAndNeverEnablesSubmissionOfAnUninitializedDraft() {
        var retries = 0
        compose.setContent {
            LegadoComposeTheme {
                ContentSearchScreen(
                    ContentSearchState(
                        loading = false,
                        loadFailed = true,
                        focusInput = false,
                        error = "failed loading",
                    ),
                    actions().copy(retry = { retries++ }),
                )
            }
        }
        compose.onNodeWithTag("content-search-query").assertIsNotEnabled()
        compose.onNodeWithTag("content-search-submit").assertIsNotEnabled()
        compose.onNodeWithTag("content-search-error").assertTextContains("failed loading")
        compose.onNodeWithTag("content-search-retry").performClick()
        assertEquals(1, retries)
    }

    private fun vm(repository: Fake): ContentSearchViewModel {
        lateinit var result: ContentSearchViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result =
                ContentSearchViewModel(repository, Options(), SavedStateHandle()) {
                    ContentSearchSession("book")
                }
            owners += ViewModelStore().apply { put("content", result) }
        }
        return result
    }

    private fun owner(): Owner {
        lateinit var result: Owner
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = Owner() }
        return result
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Options : ContentSearchOptionsRepository {
        private var value = ContentSearchOptions()

        override fun current() = value

        override fun replace(value: Boolean) =
            this.value.copy(replace = value).also { this.value = it }

        override fun regex(value: Boolean) = this.value.copy(regex = value).also { this.value = it }

        override fun restore(value: ContentSearchOptions) = value.also { this.value = it }
    }

    private class Fake(private var value: ContentSearchSession) : ContentSearchRepository {
        override suspend fun load(url: String) =
            ContentSearchLoaded(ContentSearchBook(url, "Book", 3, false, ""), emptySet())

        override fun search(book: ContentSearchBook, query: String, cacheNames: () -> Set<String>) =
            flowOf(ContentSearchUpdate(emptyList(), false, 0, 0))

        override suspend fun read(session: String) = value

        override suspend fun create(session: String, snapshot: ContentSearchSession) = value

        override suspend fun write(session: String, snapshot: ContentSearchSession) {
            if (snapshot.revision >= value.revision) value = snapshot
        }

        override suspend fun release(session: String) = Unit
    }
}
