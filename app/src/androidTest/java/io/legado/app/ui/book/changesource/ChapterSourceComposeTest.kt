package io.legado.app.ui.book.changesource

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*

class ChapterSourceComposeTest {
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
            )
            .let {
                ChapterSourceSearchRow(
                    id,
                    id,
                    it.originName,
                    "Book",
                    "Author",
                    "Latest",
                    "[1] Chapter 字数：120",
                    120,
                    30,
                    0,
                    0,
                    0,
                    GSON.toJson(it),
                )
            }

    private fun chapter(index: Int, title: String, volume: Boolean = false) =
        BookChapter(index = index, title = title, bookUrl = "old", url = "$index").let {
            ChapterSourceChapter("$index", index, title, volume, "Updated", GSON.toJson(it))
        }

    private fun actions() =
        ChapterSourceScreenActions(
            {},
            {},
            {},
            {},
            {},
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
            { _, _ -> false },
            { 1..2 },
        )

    @Test
    fun toolbarActionsAndLongPressMenuKeepOrderAndInvokeDistinctStableRows() {
        val menu = mutableListOf<ChapterSourceMenu>()
        val rowActions = mutableListOf<Pair<String, ChapterSourceRowAction>>()
        val scores = mutableListOf<Pair<String, Int>>()
        var opened = ""
        compose.setContent {
            LegadoComposeTheme {
                ChapterSourceScreen(
                    ChapterSourceState(
                        loading = false,
                        chapterTitle = "Chapter",
                        request =
                            ChapterSourceSearchRequest("Book", "Author", loadWordCount = true),
                        rows = listOf(row()),
                    ),
                    actions()
                        .copy(
                            menu = { menu += it },
                            rowAction = { id, action -> rowActions += id to action },
                            score = { id, score -> scores += id to score },
                            openToc = { opened = it },
                        ),
                )
            }
        }
        compose.onNodeWithTag("chapter-source-good-target").performClick()
        assertEquals(listOf("target" to 1), scores)
        compose.onNodeWithTag("chapter-source-row-target").performClick()
        assertEquals("target", opened)
        chapterSourceRowActions.forEach { action ->
            compose.onNodeWithTag("chapter-source-row-target").performTouchInput { longClick() }
            compose.onNodeWithTag("chapter-source-action-${action.name}").performClick()
        }
        assertEquals(chapterSourceRowActions.map { "target" to it }, rowActions)
        compose.onNodeWithTag("chapter-source-menu").performClick()
        compose.onNodeWithTag("chapter-source-menu-WordCountFilter").performClick()
        assertEquals(listOf(ChapterSourceMenu.WordCountFilter), menu)
    }

    @Test
    fun searchToolbarOpensFieldAndDirectoryHideRemainsReachable() {
        var state by
            mutableStateOf(
                ChapterSourceState(
                    loading = false,
                    request = ChapterSourceSearchRequest("Book", "Author"),
                    chapterTitle = "Chapter",
                )
            )
        var searched = 0
        var closed = 0
        compose.setContent {
            LegadoComposeTheme {
                ChapterSourceScreen(
                    state,
                    actions()
                        .copy(
                            searchOpen = { state = state.copy(searchOpen = !state.searchOpen) },
                            query = {
                                state = state.copy(request = state.request!!.copy(query = it))
                            },
                            startStop = { searched++ },
                            close = { closed++ },
                            hideToc = { state = state.copy(tocVisible = false) },
                        ),
                )
            }
        }
        compose.onNodeWithTag("chapter-source-filter").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chapter-source-query").performTextReplacement("Latest")
        compose.onNodeWithTag("chapter-source-query").assertTextContains("Latest")
        compose.onNodeWithTag("chapter-source-search").assertIsDisplayed().performClick()
        assertEquals(1, searched)
        compose.runOnIdle {
            state =
                state.copy(
                    tocVisible = true,
                    toc = ChapterSourceToc("toc", "{}", "{}", listOf(chapter(1, "Chapter")), 0),
                )
        }
        compose.onNodeWithTag("chapter-source-hide-toc").assertIsDisplayed().performClick()
        compose.onNodeWithTag("chapter-source-toc").assertDoesNotExist()
        compose.onNodeWithTag("chapter-source-close").assertIsDisplayed().performClick()
        assertEquals(1, closed)
    }

    @Test
    fun batchDirectoryVolumeHeadingCannotSelectAndCacheButtonTracksSelection() {
        val volume = chapter(0, "Volume", true)
        val target = ChapterSourceToc("toc", "{}", "{}", listOf(volume, chapter(1, "Chapter")), 1)
        var state by
            mutableStateOf(
                ChapterSourceState(
                    loading = false,
                    batch = true,
                    toc = target,
                    tocVisible = true,
                    chapterTitle = "Chapter",
                    currentOriginal = target.chapters[1],
                )
            )
        var cached = 0
        var skipped = 0
        compose.setContent {
            LegadoComposeTheme {
                ChapterSourceScreen(
                    state,
                    actions()
                        .copy(
                            chapter = { position ->
                                state =
                                    state.copy(
                                        selected =
                                            if (state.selected.isEmpty())
                                                setOf(target.chapters[position].index)
                                            else emptySet()
                                    )
                            },
                            cache = { cached++ },
                            skip = { skipped++ },
                        ),
                )
            }
        }
        compose.onNodeWithTag("chapter-source-chapter-0").assertIsNotEnabled()
        compose.onNodeWithTag("chapter-source-cache").assertIsNotEnabled()
        compose.onNodeWithTag("chapter-source-chapter-1").performClick().assertIsSelected()
        compose.onNodeWithTag("chapter-source-cache").assertIsEnabled().performClick()
        assertEquals(1, cached)
        compose.onNodeWithTag("chapter-source-chapter-1").performClick().assertIsNotSelected()
        compose.onNodeWithTag("chapter-source-cache").assertIsNotEnabled()
        compose.onNodeWithTag("chapter-source-skip").performClick()
        assertEquals(1, skipped)
    }

    @Test
    fun automationRangeDraftAndInvalidInputSurviveCompositionRestoration() {
        val restoration = StateRestorationTester(compose)
        val ranges = mutableListOf<Pair<Int, Int>>()
        val target = ChapterSourceToc("toc", "{}", "{}", listOf(chapter(1, "Chapter")), 0)
        restoration.setContent {
            LegadoComposeTheme {
                ChapterSourceScreen(
                    ChapterSourceState(loading = false, batch = true, toc = target),
                    actions()
                        .copy(
                            startAutomation = { start, end ->
                                if (start in 1..end && end <= 2) {
                                    ranges += start to end
                                    true
                                } else false
                            }
                        ),
                )
            }
        }
        compose.onNodeWithTag("chapter-source-menu").performClick()
        compose.onNodeWithTag("chapter-source-menu-Automation").performClick()
        compose.onNodeWithTag("chapter-source-range-start").performTextReplacement("0")
        compose.onNodeWithTag("chapter-source-range-confirm").performClick()
        assertTrue(ranges.isEmpty())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chapter-source-range-start").assertTextContains("0")
        compose.onNodeWithTag("chapter-source-range-start").performTextReplacement("1")
        compose.onNodeWithTag("chapter-source-range-confirm").performClick()
        assertEquals(listOf(1 to 2), ranges)
    }

    @Test
    fun emptyGroupPromptStaysVisibleAcrossRestoreAndOnlyConfirmationChangesGroup() {
        val restoration = StateRestorationTester(compose)
        val selected = mutableListOf<String>()
        var state by
            mutableStateOf(
                ChapterSourceState(
                    loading = false,
                    emptyGroup = true,
                    request = ChapterSourceSearchRequest("Book", "Author", group = "Group"),
                )
            )
        restoration.setContent {
            LegadoComposeTheme {
                ChapterSourceScreen(
                    state,
                    actions()
                        .copy(
                            group = { selected += it },
                            dismissEmpty = { state = state.copy(emptyGroup = false) },
                        ),
                )
            }
        }
        compose.onNodeWithTag("chapter-source-empty-all").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chapter-source-empty-all").assertExists().performClick()
        assertEquals(listOf(""), selected)
        compose.onNodeWithTag("chapter-source-empty-all").assertDoesNotExist()
    }

    @Test
    fun cancelledReceiptPreparationOnPauseDeliversOnceAfterResumeAndConsumesBeforeHost() {
        val content = Content()
        val body =
            ChapterSourceReceipt(
                "body",
                ChapterSourceReceiptKind.Content,
                body = "Full body",
                committed = true,
            )
        content.receipts[body.key] = body
        content.stored = seed().copy(pendingReceipt = body.key)
        val owner = mainOwner()
        var delivered = 0
        lateinit var model: ChapterSourceViewModel
        compose.setContent {
            model = remember { model(content) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ChapterSourceRoute(
                        model,
                        { true },
                        {
                            assertNull(model.state.value.pendingReceipt)
                            assertEquals("Full body", it.receipt.body)
                            delivered++
                        },
                        {},
                        {},
                        {},
                        {},
                    )
                }
            }
        }
        compose.waitUntil(5000) { !model.state.value.loading }
        assertEquals(0, delivered)
        content.receiptGate = CompletableDeferred()
        content.receiptStarted = CompletableDeferred()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { content.receiptStarted!!.isCompleted }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { content.receiptGate!!.complete(Unit) }
        compose.waitForIdle()
        assertEquals(0, delivered)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { delivered == 1 }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, delivered)
    }

    @Test
    fun automationWaitsForResumedHostAndDoesNotRepeatCompletedCacheOnRestart() {
        val content = Content()
        content.stored = seed()
        val owner = mainOwner()
        var delivered = 0
        var finished = 0
        lateinit var model: ChapterSourceViewModel
        compose.setContent {
            model = remember { model(content) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ChapterSourceRoute(model, { true }, { delivered++ }, {}, {}, {}, { finished++ })
                }
            }
        }
        compose.waitUntil(5000) { !model.state.value.loading }
        compose.runOnIdle { assertTrue(model.startAutomation(1, 1)) }
        compose.waitForIdle()
        assertEquals(0, content.cached)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { finished == 1 }
        assertEquals(1, content.cached)
        assertEquals(1, delivered)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, content.cached)
        assertEquals(1, delivered)
    }

    @Test
    fun headerAndMenuCloseDuringCommitWaitForCurrentCacheAndFinishRemainsDisabled() {
        val content =
            Content().apply {
                stored = seed()
                commitGate = CompletableDeferred()
            }
        val owner = mainOwner()
        var closed = 0
        var finished = 0
        lateinit var model: ChapterSourceViewModel
        compose.setContent {
            model = remember { model(content) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    ChapterSourceRoute(model, { true }, {}, {}, {}, { closed++ }, { finished++ })
                }
            }
        }
        compose.waitUntil(5000) { !model.state.value.loading }
        compose.runOnIdle {
            model.startAutomation(1, 1)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil(5000) { content.commitStarted.isCompleted }
        compose.onNodeWithTag("chapter-source-close").performClick()
        compose.runOnIdle {
            assertEquals(0, closed)
            assertTrue(model.state.value.automation!!.stopAfterCurrent)
        }
        compose.onNodeWithTag("chapter-source-finish").assertIsNotEnabled()
        compose.onNodeWithTag("chapter-source-menu").performClick()
        compose.onNodeWithTag("chapter-source-menu-Close").performScrollTo().performClick()
        assertEquals(0, closed)
        compose.runOnIdle { content.commitGate!!.complete(Unit) }
        compose.waitUntil(5000) { finished == 1 }
        assertEquals(1, content.cached)
        assertEquals(0, closed)
    }

    private fun seed(): ChapterSourceSession {
        val chapters = listOf(chapter(1, "第一章 开始"))
        val toc =
            ChapterSourceToc(
                "toc",
                GSON.toJson(Book(bookUrl = "target", origin = "target")),
                GSON.toJson(BookSource(bookSourceUrl = "target")),
                chapters,
                0,
            )
        return ChapterSourceSession(
            ChapterSourceSearchRequest(
                "Book",
                "Author",
                originalBookJson =
                    GSON.toJson(
                        Book(bookUrl = "old", origin = "old", name = "Book", author = "Author")
                    ),
            ),
            1,
            "第一章 开始",
            true,
            originalChapters = chapters,
            toc = toc,
            tocVisible = true,
        )
    }

    private fun model(content: Content): ChapterSourceViewModel =
        ChapterSourceViewModel(
                object : ChapterSourceSearchRepository {
                    override suspend fun cached(request: ChapterSourceSearchRequest) =
                        ChapterSourceSearchUpdate(listOf(row()), false)

                    override fun search(
                        request: ChapterSourceSearchRequest,
                        previous: List<ChapterSourceSearchRow>,
                    ) = flowOf(ChapterSourceSearchUpdate(listOf(row()), false))

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
                SavedStateHandle(),
            ) {
                seed()
            }
            .also { owners += ViewModelStore().apply { put("chapter", it) } }

    private fun mainOwner(): Owner {
        lateinit var result: Owner
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = Owner() }
        return result
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle
            get() = registry
    }

    private inner class Content : ChapterSourceContentRepository {
        var stored: ChapterSourceSession? = null
        val receipts = mutableMapOf<String, ChapterSourceReceipt>()
        var cached = 0
        var commitGate: CompletableDeferred<Unit>? = null
        val commitStarted = CompletableDeferred<Unit>()
        var receiptGate: CompletableDeferred<Unit>? = null
        var receiptStarted: CompletableDeferred<Unit>? = null

        override suspend fun original(bookJson: String) = seed().originalChapters

        override suspend fun toc(row: ChapterSourceSearchRow, index: Int, title: String) =
            requireNotNull(seed().toc)

        override suspend fun content(session: String, toc: ChapterSourceToc, position: Int) =
            error("unused")

        override suspend fun cache(
            session: String,
            toc: ChapterSourceToc,
            positions: List<Int>,
            originalBookJson: String,
            originalChapter: ChapterSourceChapter,
            onCommit: suspend () -> Unit,
        ): ChapterSourceReceipt {
            cached++
            return withContext(NonCancellable) {
                onCommit()
                commitStarted.complete(Unit)
                commitGate?.await()
                ChapterSourceReceipt(
                        "cache",
                        ChapterSourceReceiptKind.Cache,
                        body = "Cached",
                        chapterIndex = originalChapter.index,
                        committed = true,
                    )
                    .also { receipts[it.key] = it }
            }
        }

        override suspend fun read(session: String) = stored

        override suspend fun write(session: String, snapshot: ChapterSourceSession) {
            if ((stored?.revision ?: -1) <= snapshot.revision) stored = snapshot
        }

        override suspend fun receipt(session: String, key: String): ChapterSourceReceipt {
            receiptStarted?.complete(Unit)
            receiptGate?.let { withContext(NonCancellable) { it.await() } }
            return receipts.getValue(key)
        }

        override suspend fun recoverCache(
            session: String,
            originalBookJson: String,
            originalChapters: List<ChapterSourceChapter>,
        ) = receipts.values.lastOrNull { it.kind == ChapterSourceReceiptKind.Cache && !it.consumed }

        override suspend fun consume(session: String, key: String) {
            receipts[key] = receipts.getValue(key).copy(consumed = true)
        }

        override suspend fun abandonUncommittedCache(session: String) = Unit

        override suspend fun change(
            session: String,
            toc: ChapterSourceToc,
            deleteAfterId: String?,
        ) = error("unused")

        override suspend fun deleteSource(row: ChapterSourceSearchRow) = Unit

        override suspend fun disableSource(row: ChapterSourceSearchRow) = Unit

        override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) = Unit

        override suspend fun score(row: ChapterSourceSearchRow, score: Int) = Unit

        override suspend fun groups() = listOf("Group")
    }
}
