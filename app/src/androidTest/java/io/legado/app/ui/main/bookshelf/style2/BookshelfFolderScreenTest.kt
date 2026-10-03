package io.legado.app.ui.main.bookshelf.style2

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookshelfBook
import io.legado.app.data.image.*
import io.legado.app.data.repository.*
import io.legado.app.ui.components.cover.ComposeGroupCover
import io.legado.app.ui.main.bookshelf.components.BookshelfBookCardModel
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookshelfFolderScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun books(group: Long) =
        (0..59).map { index ->
            BookshelfFolderBook(
                BookshelfBookCardModel(
                    "$group-book$index",
                    "Book $index",
                    "Author",
                    "Chapter",
                    "Latest",
                ),
                null,
                null,
            )
        }

    private fun initial(group: Long = BookGroup.IdRoot) =
        BookshelfFolderState(
            groupId = group,
            groups =
                listOf(1L, 2L, 4L).map {
                    BookshelfFolderGroup(it, "Group $it", null, emptyList(), 0, true, false)
                },
            books = books(group),
            loading = false,
        )

    @Composable
    private fun Content(
        state: BookshelfFolderState,
        change: (BookshelfFolderState) -> Unit,
        edit: (Long) -> Unit = {},
        open: (String) -> Unit = {},
        info: (String) -> Unit = {},
        swipe: (Int) -> Unit = {},
        refresh: () -> Unit = {},
        ack: (Long, Int) -> Unit = { _, _ -> },
        groupCover: @Composable (BookshelfFolderGroup, Modifier) -> Unit = { group, modifier ->
            Box(modifier.testTag("folder-cover-${group.id}"))
        },
    ) {
        BookshelfFolderScreen(
            state,
            { id -> change(state.copy(groupId = id, books = books(id))) },
            edit,
            open,
            info,
            {},
            { change(state.copy(groupId = BookGroup.IdRoot, books = books(BookGroup.IdRoot))) },
            { offset ->
                swipe(offset)
                state.groups.getOrNull(state.index + offset)?.let {
                    change(state.copy(groupId = it.id, books = books(it.id)))
                }
            },
            refresh,
            {},
            {},
            ack,
            {},
            Modifier.width(360.dp).height(600.dp),
            bookCover = { book, modifier ->
                Box(modifier.testTag("folder-book-cover-${book.card.key}"))
            },
            groupCover = groupCover,
        )
    }

    @Test
    fun rootGroupAndBookClicksLongClicksAndToolbarBackKeepContracts() {
        var state by mutableStateOf(initial())
        val edits = mutableListOf<Long>()
        val opens = mutableListOf<String>()
        val infos = mutableListOf<String>()
        compose.setContent {
            LegadoComposeTheme {
                Content(state, { state = it }, { edits += it }, { opens += it }, { infos += it })
            }
        }
        compose.onNodeWithTag("shelf-back").assertDoesNotExist()
        compose.onNodeWithTag("shelf-group-1").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf-group-1").performClick()
        compose.onNodeWithTag("shelf-back").assertIsDisplayed()
        compose.onNodeWithTag("shelf-book-1-book0").performClick()
        compose.onNodeWithTag("shelf-book-1-book0").performTouchInput { longClick() }
        compose.onNodeWithTag("shelf-back").performClick()
        compose.onNodeWithTag("shelf-group-1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(1L), edits)
            assertEquals(listOf("1-book0"), opens)
            assertEquals(opens, infos)
        }
    }

    @Test
    fun horizontalSwipeCapturesOnlyEligibleGroupsAndAccessibilityCanSwitchThem() {
        var state by mutableStateOf(initial(2))
        val swipes = mutableListOf<Int>()
        var opened = 0
        compose.setContent {
            LegadoComposeTheme {
                Content(state, { state = it }, open = { opened++ }, swipe = { swipes += it })
            }
        }
        compose.onNodeWithTag("bookshelf-folder-2").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("bookshelf-folder-4").assertExists()
        compose.onNodeWithTag("bookshelf-folder-4").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(4L, state.groupId)
            assertEquals(listOf(1), swipes)
            assertEquals(0, opened)
        }
        compose.onNodeWithTag("bookshelf-folder-4").performTouchInput { swipeRight() }
        val actions =
            compose
                .onNodeWithTag("bookshelf-folder-2")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first().action()) }
        compose.runOnIdle {
            assertEquals(1L, state.groupId)
            assertEquals(listOf(1, -1, -1), swipes)
        }
    }

    @Test
    fun cancelReverseAndVerticalGesturesNeverSwitchGroupsOrOpenBooks() {
        var state by mutableStateOf(initial(2))
        var opened = 0
        var switched = 0
        compose.setContent {
            LegadoComposeTheme {
                Content(state, { state = it }, open = { opened++ }, swipe = { switched++ })
            }
        }
        compose.onNodeWithTag("bookshelf-folder-2").performTouchInput {
            down(center)
            moveBy(Offset(-100f, 0f))
            cancel()
        }
        compose.onNodeWithTag("bookshelf-folder-2").performTouchInput {
            down(center)
            moveBy(Offset(-100f, 0f))
            moveBy(Offset(200f, 0f))
            up()
        }
        compose.onNodeWithTag("bookshelf-folder-list").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertEquals(2L, state.groupId)
            assertEquals(0, switched)
            assertEquals(0, opened)
        }
    }

    @Test
    fun eachGroupAndRootRestoreIndependentScrollAfterLoadingAndRecreation() {
        var state by mutableStateOf(initial())
        val tester = StateRestorationTester(compose)
        tester.setContent { LegadoComposeTheme { Content(state, { state = it }) } }
        compose
            .onNodeWithTag("bookshelf-folder-list")
            .performScrollToNode(hasTestTag("shelf-book--100-book40"))
        compose.runOnIdle { state = state.copy(groupId = 1, books = books(1)) }
        compose
            .onNodeWithTag("bookshelf-folder-list")
            .performScrollToNode(hasTestTag("shelf-book-1-book25"))
        compose.runOnIdle { state = state.copy(groupId = 2, books = books(2)) }
        compose
            .onNodeWithTag("bookshelf-folder-list")
            .performScrollToNode(hasTestTag("shelf-book-2-book50"))
        compose.runOnIdle { state = state.copy(groupId = 1, books = emptyList(), loading = true) }
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bookshelf-folder-loading").assertExists()
        compose.runOnIdle { state = state.copy(books = books(1), loading = false) }
        compose.onNodeWithTag("shelf-book-1-book25").assertIsDisplayed()
        compose.runOnIdle {
            state = state.copy(groupId = BookGroup.IdRoot, books = emptyList(), loading = true)
        }
        compose.onNodeWithTag("bookshelf-folder-list").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(books = books(BookGroup.IdRoot), loading = false) }
        compose.onNodeWithTag("shelf-book--100-book40").assertIsDisplayed()
    }

    @Test
    fun fastScrollerTargetsActualLastBookInMixedRootListAndGrid() {
        var state by
            mutableStateOf(initial().copy(settings = BookshelfPageSettings(fastScroller = true)))
        compose.setContent { LegadoComposeTheme { Content(state, { state = it }) } }
        compose.onNodeWithTag("bookshelf-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            assertTrue(it(1f))
        }
        compose.onNodeWithTag("shelf-book--100-book59").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(layout = 3)) }
        compose.onNodeWithTag("bookshelf-fast-scroll").performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            assertTrue(it(1f))
        }
        compose.onNodeWithTag("shelf-book--100-book59").assertIsDisplayed()
    }

    @Test
    fun metadataUpdatesKeepScrolledItemBoundsAndGotoTopWaitsForLoadedData() {
        var state by mutableStateOf(initial(1))
        val acks = mutableListOf<Pair<Long, Int>>()
        compose.setContent {
            LegadoComposeTheme {
                Content(state, { state = it }, ack = { group, token -> acks += group to token })
            }
        }
        compose
            .onNodeWithTag("bookshelf-folder-list")
            .performScrollToNode(hasTestTag("shelf-book-1-book35"))
        val before = compose.onNodeWithTag("shelf-book-1-book35").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            state =
                state.copy(
                    books =
                        state.books.map { it.copy(card = it.card.copy(currentChapter = "Updated")) }
                )
        }
        val after = compose.onNodeWithTag("shelf-book-1-book35").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        val loaded = state
        compose.runOnIdle {
            state = state.copy(books = emptyList(), loading = true, scrollRequest = 7)
        }
        compose.runOnIdle {
            assertTrue(acks.isEmpty())
            state = loaded.copy(scrollRequest = 7)
        }
        compose.onNodeWithTag("shelf-book-1-book0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(1L to 7), acks) }
    }

    @Test
    fun rootRefreshAndDisabledGroupOrEmptyListRespectAvailability() {
        var state by mutableStateOf(initial().copy(books = emptyList()))
        var refreshes = 0
        compose.setContent {
            LegadoComposeTheme { Content(state, { state = it }, refresh = { refreshes++ }) }
        }
        compose.onNodeWithTag("bookshelf-folder--100").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state =
                state.copy(
                    groupId = 2,
                    groups = state.groups.map { if (it.id == 2L) it.copy(refresh = false) else it },
                    books = books(2),
                )
        }
        compose.onNodeWithTag("bookshelf-folder-2").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, refreshes)
            state =
                state.copy(groupId = BookGroup.IdRoot, groups = emptyList(), books = emptyList())
        }
        compose.onNodeWithTag("bookshelf-folder-empty").assertIsDisplayed()
        compose.onNodeWithTag("bookshelf-folder--100").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    private fun bitmap(color: Int) =
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private inner class Colors : CoverRepository {
        override val configurations =
            MutableStateFlow<CoverConfiguration?>(
                CoverConfiguration(
                    bitmap(android.graphics.Color.WHITE),
                    drawName = false,
                    drawAuthor = false,
                )
            )

        override fun refreshConfiguration() = Unit

        override suspend fun title(
            request: CoverRequest,
            configuration: CoverConfiguration,
            width: Int,
            height: Int,
        ): Bitmap? = null

        override suspend fun load(
            request: CoverRequest,
            configuration: CoverConfiguration,
            width: Int,
            height: Int,
        ): CoverLoadResult {
            val color =
                when (request.path) {
                    "red" -> android.graphics.Color.RED
                    "green" -> android.graphics.Color.GREEN
                    "blue" -> android.graphics.Color.BLUE
                    "yellow" -> android.graphics.Color.YELLOW
                    else -> android.graphics.Color.MAGENTA
                }
            return CoverLoadResult(CoverImage.Static(bitmap(color)), false)
        }
    }

    @Test
    fun rootFourSlotCoverRendersAllPreviewsAndCustomCoverReplacesThem() {
        val previews =
            listOf("red", "green", "blue", "yellow").map { path ->
                BookshelfBook(
                    path,
                    "https://source",
                    path,
                    "Author",
                    path,
                    null,
                    BookType.text,
                    1,
                    true,
                    0,
                    0,
                    0,
                )
            }
        var state by
            mutableStateOf(
                initial()
                    .copy(
                        groups =
                            listOf(
                                BookshelfFolderGroup(1, "Colors", null, previews, 0, true, false)
                            ),
                        books = emptyList(),
                    )
            )
        val repo = Colors()
        compose.setContent {
            LegadoComposeTheme {
                Content(
                    state,
                    { state = it },
                    groupCover = { group, modifier ->
                        ComposeGroupCover(
                            group.cover,
                            group.preview,
                            modifier.testTag("real-folder-cover"),
                            repository = repo,
                        )
                    },
                )
            }
        }
        fun colors(): List<Color> {
            val pixels =
                compose
                    .onNodeWithTag("real-folder-cover", useUnmergedTree = true)
                    .captureToImage()
                    .toPixelMap()
            return listOf(
                pixels[pixels.width / 4, pixels.height / 4],
                pixels[pixels.width * 3 / 4, pixels.height / 4],
                pixels[pixels.width / 4, pixels.height * 3 / 4],
                pixels[pixels.width * 3 / 4, pixels.height * 3 / 4],
            )
        }
        compose.waitUntil { colors() == listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow) }
        compose
            .onNodeWithTag("real-folder-cover", useUnmergedTree = true)
            .assertWidthIsEqualTo(72.dp)
            .assertHeightIsEqualTo(96.dp)
        compose.runOnIdle {
            state = state.copy(groups = state.groups.map { it.copy(cover = "custom") })
        }
        compose.waitUntil { colors().all { it == Color.Magenta } }
    }
}
