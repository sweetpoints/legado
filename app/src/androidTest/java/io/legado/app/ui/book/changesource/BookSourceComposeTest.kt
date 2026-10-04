package io.legado.app.ui.book.changesource

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.BookType
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class BookSourceComposeTest {
    @get:Rule val compose = createComposeRule()
    private val owners = mutableListOf<ViewModelStore>()

    @After
    fun clear() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owners.forEach { it.clear() } }
    }

    private fun row(id: String = "target") =
        SearchBook(
                bookUrl = id,
                origin = id,
                originName = "Source $id",
                name = "Book",
                author = "Author",
                type = BookType.text,
            )
            .let {
                ChapterSourceSearchRow(
                    id,
                    id,
                    it.originName,
                    it.name,
                    it.author,
                    "Latest",
                    "[1] Chapter 字数：120",
                    120,
                    30,
                    0,
                    0,
                    it.type,
                    GSON.toJson(it),
                )
            }

    private fun actions() =
        BookSourceScreenActions(
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            { _, _ -> },
            { _, _ -> },
            {},
            {},
            {},
            {},
            {},
        )

    @Test
    fun currentRowIsSelectedDoesNotChangeAndMeasuredFieldsAndScoresHaveReachableActions() {
        val current = row("current")
        val target = row()
        val chosen = mutableListOf<String>()
        val scored = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            LegadoComposeTheme {
                BookSourceScreen(
                    BookSourceState(
                        loading = false,
                        request =
                            ChapterSourceSearchRequest(
                                "Book",
                                "Author",
                                currentBookUrl = current.id,
                                loadWordCount = true,
                            ),
                        rows = listOf(current, target),
                    ),
                    actions()
                        .copy(
                            choose = { chosen += it },
                            score = { id, score -> scored += id to score },
                        ),
                )
            }
        }
        compose.onNodeWithTag("book-source-row-current").assertIsSelected().performClick()
        assertTrue(chosen.isEmpty())
        compose.onNodeWithTag("book-source-row-target").performScrollTo().performClick()
        assertEquals(listOf("target"), chosen)
        compose.onNodeWithTag("book-source-count-target", useUnmergedTree = true)
            .assertTextEquals(checkNotNull(target.wordCountText))
        compose.onNodeWithTag("book-source-time-target", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("book-source-good-target").performClick()
        compose.onNodeWithTag("book-source-bad-target").performClick()
        assertEquals(listOf("target" to 1, "target" to -1), scored)
    }

    @Test
    fun longPressActionsKeepOrderAndDeleteRequiresConfirmationThatSurvivesRestoration() {
        val restore = StateRestorationTester(compose)
        val selected = mutableListOf<Pair<String, BookSourceRowAction>>()
        restore.setContent {
            LegadoComposeTheme {
                BookSourceScreen(
                    BookSourceState(
                        loading = false,
                        request = ChapterSourceSearchRequest("Book", "Author"),
                        rows = listOf(row()),
                    ),
                    actions().copy(rowAction = { id, action -> selected += id to action }),
                )
            }
        }
        for (action in bookSourceRowActions.filter { it != BookSourceRowAction.Delete }) {
            compose.onNodeWithTag("book-source-row-target").performTouchInput { longClick() }
            bookSourceRowActions.forEach {
                compose.onNodeWithTag("book-source-row-action-${it.name}").assertExists()
            }
            compose.onNodeWithTag("book-source-row-action-${action.name}").performClick()
        }
        assertEquals(
            bookSourceRowActions.filter { it != BookSourceRowAction.Delete }.map { "target" to it },
            selected,
        )
        compose.onNodeWithTag("book-source-row-target").performTouchInput { longClick() }
        compose.onNodeWithTag("book-source-row-action-Delete").performClick()
        assertEquals(4, selected.size)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("book-source-delete-cancel").performClick()
        assertEquals(4, selected.size)
        compose.onNodeWithTag("book-source-row-target").performTouchInput { longClick() }
        compose.onNodeWithTag("book-source-row-action-Delete").performClick()
        compose.onNodeWithTag("book-source-delete-confirm").performClick()
        assertEquals("target" to BookSourceRowAction.Delete, selected.last())
    }

    @Test
    fun searchToolbarBackUsesLocalizedLabelAndGroupMenuInvokesExactGroup() {
        var state by
            mutableStateOf(
                BookSourceState(
                    loading = false,
                    request = ChapterSourceSearchRequest("Book", "Author"),
                )
            )
        var closed = 0
        var started = 0
        val groups = mutableListOf<String>()
        val menu = mutableListOf<BookSourceMenu>()
        val back =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(io.legado.app.R.string.back)
        compose.setContent {
            LegadoComposeTheme {
                BookSourceScreen(
                    state,
                    actions()
                        .copy(
                            close = { closed++ },
                            searchOpen = { state = state.copy(searchOpen = !state.searchOpen) },
                            query = {
                                state = state.copy(request = state.request!!.copy(query = it))
                            },
                            startStop = { started++ },
                            group = { groups += it },
                            menu = { menu += it },
                        ),
                    groups = listOf("Group"),
                )
            }
        }
        compose.onNodeWithContentDescription(back).assertIsDisplayed()
        compose.onNodeWithTag("book-source-search-open").performClick()
        compose.onNodeWithTag("book-source-query").performTextReplacement("Latest")
        compose.onNodeWithTag("book-source-query").assertTextContains("Latest")
        compose.onNodeWithTag("book-source-start-stop").performClick()
        assertEquals(1, started)
        compose.onNodeWithTag("book-source-menu").performClick()
        compose.onNodeWithTag("book-source-menu-WordCountFilter").performScrollTo().performClick()
        assertEquals(listOf(BookSourceMenu.WordCountFilter), menu)
        compose.onNodeWithTag("book-source-menu").performClick()
        compose.onNodeWithTag("book-source-menu-Group").performScrollTo().performClick()
        compose.onNodeWithTag("book-source-group-Group").performClick()
        assertEquals(listOf("Group"), groups)
        compose.onNodeWithTag("book-source-close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun mismatchAndEmptyGroupConfirmationsRemainPendingAcrossRestorationAndCancelNeverConfirms() {
        val restore = StateRestorationTester(compose)
        var state by
            mutableStateOf(
                BookSourceState(
                    loading = false,
                    request = ChapterSourceSearchRequest("Book", "Author", group = "Group"),
                    mismatchId = "target",
                )
            )
        var confirmed = 0
        var all = 0
        restore.setContent {
            LegadoComposeTheme {
                BookSourceScreen(
                    state,
                    actions()
                        .copy(
                            confirmMismatch = {
                                confirmed++
                                state = state.copy(mismatchId = null)
                            },
                            dismissMismatch = { state = state.copy(mismatchId = null) },
                            searchAll = {
                                all++
                                state = state.copy(emptyGroup = false)
                            },
                            dismissEmpty = { state = state.copy(emptyGroup = false) },
                        ),
                )
            }
        }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("book-source-mismatch-cancel").performClick()
        assertEquals(0, confirmed)
        compose.runOnIdle { state = state.copy(mismatchId = "target") }
        compose.onNodeWithTag("book-source-mismatch-confirm").performClick()
        assertEquals(1, confirmed)
        compose.runOnIdle { state = state.copy(emptyGroup = true) }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("book-source-empty-cancel").performClick()
        assertEquals(0, all)
        compose.runOnIdle { state = state.copy(emptyGroup = true) }
        compose.onNodeWithTag("book-source-empty-confirm").performClick()
        assertEquals(1, all)
    }

    @Test
    fun normalChangeHasCancelActionWhileAutomaticReplacementCannotBeCancelled() {
        var state by
            mutableStateOf(
                BookSourceState(loading = false, changing = true, changeCancelable = true)
            )
        var cancelled = 0
        compose.setContent {
            LegadoComposeTheme {
                BookSourceScreen(
                    state,
                    actions()
                        .copy(
                            cancelChange = {
                                cancelled++
                                state = state.copy(changing = false)
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("book-source-change-cancel").performClick()
        assertEquals(1, cancelled)
        compose.runOnIdle { state = state.copy(changing = true, changeCancelable = false) }
        compose.onNodeWithTag("book-source-change-cancel").assertDoesNotExist()
    }

    @Test
    fun pausedReadPreparationCancelsAndResumeDeliversOnceConsumedBeforeHostAndNormalChangeCloses() {
        val content = Content()
        content.stored = seed().copy(pendingReceipt = "receipt")
        val owner = mainOwner()
        var delivered = 0
        var closed = 0
        lateinit var model: BookSourceViewModel
        compose.setContent {
            model = remember { model(content) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookSourceRoute(
                        model,
                        { true },
                        {
                            assertNull(model.state.value.pendingReceipt)
                            assertTrue(model.state.value.finished)
                            assertEquals("target", it.book.bookUrl)
                            delivered++
                        },
                        {},
                        {},
                        { closed++ },
                    )
                }
            }
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            !model.state.value.loading
        }
        assertEquals(0, delivered)
        content.gate = CompletableDeferred()
        content.started = CompletableDeferred()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            content.started!!.isCompleted
        }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            content.gate!!.complete(Unit)
        }
        compose.waitForIdle()
        assertEquals(0, delivered)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            delivered == 1
        }
        assertEquals(1, closed)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, delivered)
    }

    @Test
    fun recreatedModelAfterConsumedReceiptDoesNotReplayHostCallback() {
        val content = Content()
        content.stored = seed().copy(pendingReceipt = "receipt")
        val saved = SavedStateHandle()
        val owner = mainOwner()
        var delivered = 0
        var generation by mutableIntStateOf(0)
        lateinit var model: BookSourceViewModel
        compose.setContent {
            key(generation) {
                model = remember { model(content, saved) }
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    LegadoComposeTheme {
                        BookSourceRoute(model, { true }, { delivered++ }, {}, {}, {})
                    }
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            delivered == 1
        }
        compose.runOnIdle {
            model.stop()
            generation++
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            !model.state.value.loading
        }
        compose.waitForIdle()
        assertEquals(1, delivered)
        assertNull(model.state.value.pendingReceipt)
        assertTrue(model.state.value.finished)
    }

    @Test
    fun restoredRelativeWarningWaitsForResumedHostAndIsConsumedBeforeToastWithoutRestartReplay() {
        val content = Content()
        content.stored = seed()
        val owner = mainOwner()
        var warnings = 0
        val saved = SavedStateHandle(mapOf("relativeWarning" to true, "relativeWarned" to true))
        lateinit var model: BookSourceViewModel
        compose.setContent {
            model = remember { model(content, saved) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    BookSourceRoute(
                        model,
                        { true },
                        {},
                        {},
                        {},
                        {},
                        warning = {
                            assertFalse(model.state.value.relativeWarning)
                            warnings++
                        },
                    )
                }
            }
        }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            !model.state.value.loading
        }
        assertEquals(0, warnings)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) {
            compose.mainClock.advanceTimeByFrame()
            warnings == 1
        }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, warnings)
    }

    @Test
    fun currentRowArrivingAfterFirstSearchResultIsAutomaticallyLocatedWithoutClickingIt() {
        var state by
            mutableStateOf(
                BookSourceState(
                    loading = false,
                    request =
                        ChapterSourceSearchRequest("Book", "Author", currentBookUrl = "current"),
                    rows = listOf(row("first")),
                )
            )
        compose.setContent { LegadoComposeTheme { BookSourceScreen(state, actions()) } }
        compose.onNodeWithTag("book-source-row-first").assertIsDisplayed()
        compose.runOnIdle {
            state =
                state.copy(
                    rows = listOf(row("first")) + (0..15).map { row("result-$it") } + row("current")
                )
        }
        compose.onNodeWithTag("book-source-row-current").assertIsDisplayed().assertIsSelected()
    }

    private fun seed() =
        BookSourceChangeSession(
            ChapterSourceSearchRequest(
                "Book",
                "Author",
                originalBookJson =
                    GSON.toJson(
                        Book(
                            bookUrl = "old",
                            name = "Book",
                            author = "Author",
                            type = BookType.text,
                        )
                    ),
                currentBookUrl = "old",
            ),
            rows = listOf(row()),
        )

    private fun model(content: Content, saved: SavedStateHandle = SavedStateHandle()) =
        BookSourceViewModel(
                object : BookSourceSearchRepository {
                    override suspend fun cached(request: ChapterSourceSearchRequest) =
                        ChapterSourceSearchUpdate(listOf(row()), false)

                    override fun search(
                        request: ChapterSourceSearchRequest,
                        previous: List<ChapterSourceSearchRow>,
                    ) = flowOf(ChapterSourceSearchUpdate(listOf(row()), false))

                    override fun search(
                        request: ChapterSourceSearchRequest,
                        previous: List<ChapterSourceSearchRow>,
                        origin: String?,
                    ) = search(request, previous)

                    override fun measure(
                        request: ChapterSourceSearchRequest,
                        previous: List<ChapterSourceSearchRow>,
                        missingOnly: Boolean,
                    ) = flowOf(ChapterSourceSearchUpdate(previous, false))

                    override suspend fun project(
                        request: ChapterSourceSearchRequest,
                        rows: List<ChapterSourceSearchRow>,
                    ) = rows
                },
                content,
                object : ChapterSourceSettingsRepository {
                    override suspend fun load() =
                        ChapterSourceSettings("", true, false, false, false, false, 0, 0, 0)

                    override suspend fun toggle(option: ChapterSourceOption) = load()

                    override suspend fun group(value: String) = load().copy(group = value)
                },
                saved,
            ) {
                seed()
            }
            .also { owners += ViewModelStore().apply { put("book", it) } }

    private fun mainOwner(): Owner {
        lateinit var value: Owner
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = Owner() }
        return value
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Content : BookSourceChangeRepository {
        var stored: BookSourceChangeSession? = null
        var value =
            BookSourceChangeReceipt(
                "receipt",
                GSON.toJson(Book(bookUrl = "target", origin = "source")),
                GSON.toJson(BookSource(bookSourceUrl = "source")),
                emptyList(),
            )
        var gate: CompletableDeferred<Unit>? = null
        var started: CompletableDeferred<Unit>? = null

        override suspend fun read(session: String) = stored

        override suspend fun write(session: String, snapshot: BookSourceChangeSession) {
            if ((stored?.revision ?: -1) <= snapshot.revision) stored = snapshot
        }

        override suspend fun receipt(session: String, key: String): BookSourceChangeReceipt {
            started?.complete(Unit)
            gate?.await()
            return value
        }

        override suspend fun consume(session: String, key: String) {
            value = value.copy(consumed = true)
        }

        override suspend fun complete(session: String, key: String) {
            value = value.copy(acknowledged = true)
        }

        override suspend fun prepare(
            session: String,
            row: ChapterSourceSearchRow,
            deleteAfter: ChapterSourceSearchRow?,
        ) = value.copy(deleteAfter = deleteAfter)

        override suspend fun delete(row: ChapterSourceSearchRow) = Unit

        override suspend fun disable(row: ChapterSourceSearchRow) = Unit

        override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) = Unit

        override suspend fun score(row: ChapterSourceSearchRow, score: Int) = Unit
    }
}
