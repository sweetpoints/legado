package io.legado.app.ui.association

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun item(key: String, status: BookImportStatus = BookImportStatus.New) =
        BookImportEntry(
            key,
            "{}",
            "{}",
            null,
            null,
            emptyList(),
            null,
            "Source $key",
            "https://$key",
            null,
            (1..45).joinToString("\n") { "Comment line $it" },
            true,
            null,
            true,
            false,
            status,
            true,
            status != BookImportStatus.Existing,
        )

    @Test
    fun checkboxRowAndCodeHaveIndependentStableKeys() {
        var state by
            mutableStateOf(
                BookImportUiState(
                    listOf(item("a"), item("b", BookImportStatus.Existing)),
                    setOf("a"),
                    loading = false,
                )
            )
        val codes = mutableListOf<String>()
        compose.setContent {
            Content(
                state,
                toggle = {
                    state =
                        state.copy(
                            selected =
                                if (it in state.selected) state.selected - it
                                else state.selected + it
                        )
                },
                code = { codes += it },
            )
        }
        compose.onNodeWithTag("book-import-check-b").performClick().assertIsOn()
        compose.onNodeWithTag("book-import-row-b").performClick()
        compose.onNodeWithTag("book-import-check-b").assertIsOff()
        compose.onNodeWithTag("book-import-code-b").performClick()
        compose.runOnIdle {
            assertEquals(listOf("b"), codes)
            assertEquals(setOf("a"), state.selected)
        }
        compose.onNodeWithText(context.getString(R.string.import_status_exist)).assertExists()
    }

    @Test
    fun commentClickExpandsTo39LinesWithoutChangingSelection() {
        var state by
            mutableStateOf(
                BookImportUiState(
                    listOf(item("a")),
                    setOf("a"),
                    loading = false,
                    preferences = BookImportPreferences(showComment = true),
                )
            )
        compose.setContent {
            Content(
                state,
                expand = {
                    state =
                        state.copy(
                            expanded =
                                if (it in state.expanded) state.expanded - it
                                else state.expanded + it
                        )
                },
            )
        }
        fun lines(): Int {
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("book-import-comment-a").performSemanticsAction(
                SemanticsActions.GetTextLayoutResult
            ) {
                it(results)
            }
            return results.single().lineCount
        }
        assertEquals(3, lines())
        compose.onNodeWithTag("book-import-comment-a").performClick()
        assertEquals(39, lines())
        compose.onNodeWithTag("book-import-check-a").assertIsOn()
        compose.onNodeWithTag("book-import-comment-a").performClick()
        assertEquals(3, lines())
    }

    @Test
    fun pendingRefreshBlocksAllMutationsAndCancelButKeepsSearch() {
        compose.setContent {
            Content(BookImportUiState(listOf(item("a")), loading = false, pendingRefresh = true))
        }
        listOf("check-a", "code-a", "menu", "group", "confirm", "cancel", "select-visible")
            .forEach {
                compose.onNodeWithTag("book-import-$it").assertIsNotEnabled()
            }
        compose.onNodeWithTag("book-import-progress").assertExists()
        compose.onNodeWithTag("book-import-search").assertIsEnabled()
    }

    @Test
    fun automaticModeDisablesManualMenuAndPreferenceRowsPreserveOrder() {
        val menus = mutableListOf<BookImportMenu>()
        compose.setContent {
            Content(
                BookImportUiState(listOf(item("a")), loading = false, automatic = true),
                menu = { menus += it },
            )
        }
        compose.onNodeWithTag("book-import-menu").performClick()
        compose.onNodeWithTag("book-import-menu-Manual").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithTag("book-import-menu-RememberGroup")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(listOf(BookImportMenu.RememberGroup), menus) }
        assertEquals(BookImportMenu.ShowComment.ordinal + 1, BookImportMenu.RememberGroup.ordinal)
    }

    @Test
    fun groupDraftCancelAndAcceptUseFormCallbacks() {
        var state by
            mutableStateOf(
                BookImportUiState(
                    listOf(item("a")),
                    loading = false,
                    groupOpen = true,
                    groupDraft = "before",
                )
            )
        var accepts = 0
        var closes = 0
        compose.setContent {
            Content(
                state,
                draft = { state = state.copy(groupDraft = it) },
                add = { state = state.copy(addGroupDraft = it) },
                accept = { accepts++ },
                closeGroup = { closes++ },
            )
        }
        compose.onNodeWithTag("book-import-group-name").performTextReplacement("after")
        compose.onNodeWithTag("book-import-add-group").performClick().assertIsOn()
        compose.onNodeWithTag("book-import-group-ok").performClick()
        compose.onNodeWithTag("book-import-group-cancel").performClick()
        compose.runOnIdle {
            assertEquals("after", state.groupDraft)
            assertNull(state.group)
            assertEquals(1, accepts)
            assertEquals(1, closes)
        }
    }

    @Test
    fun shortWindowRetainsReachableFooterAndScrollableRows() {
        val state = BookImportUiState((0..29).map { item("$it") }, loading = false)
        var confirms = 0
        compose.setContent { Content(state, confirm = { confirms++ }, height = 320) }
        compose.onNodeWithTag("book-import-list").performScrollToIndex(29)
        compose.onNodeWithTag("book-import-code-29").assertExists()
        compose.onNodeWithTag("book-import-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, confirms) }
    }

    @Test
    fun emptyAndErrorHaveIconWhileValidResultsHideIt() {
        var state by mutableStateOf(BookImportUiState(loading = false))
        compose.setContent { Content(state) }
        compose.onNodeWithTag("book-import-empty-icon").assertExists()
        compose.runOnIdle { state = state.copy(error = "format error") }
        compose.onNodeWithTag("book-import-error").assertTextEquals("format error")
        compose.onNodeWithTag("book-import-empty-icon").assertExists()
        compose.runOnIdle { state = state.copy(items = listOf(item("a")), error = null) }
        compose.onNodeWithTag("book-import-empty-icon").assertDoesNotExist()
        compose.onNodeWithTag("book-import-check-a").assertExists()
    }

    @Test
    fun newAndUpdateMenusDispatchDistinctSelectionActionsAndManualIsDisabledInAutomaticMode() {
        val menus = mutableListOf<BookImportMenu>()
        val state = BookImportUiState(listOf(item("a")), loading = false, automatic = true)
        compose.setContent { Content(state, menu = menus::add) }
        compose.onNodeWithTag("book-import-menu").performClick()
        compose.onNodeWithTag("book-import-menu-Manual").assertIsNotEnabled()
        compose.onNodeWithTag("book-import-menu-SelectNew").performScrollTo().performClick()
        compose.onNodeWithTag("book-import-menu").performClick()
        compose.onNodeWithTag("book-import-menu-SelectUpdate").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(BookImportMenu.SelectNew, BookImportMenu.SelectUpdate), menus)
        }
    }

    @Composable
    private fun Content(
        state: BookImportUiState,
        toggle: (String) -> Unit = {},
        code: (String) -> Unit = {},
        expand: (String) -> Unit = {},
        menu: (BookImportMenu) -> Unit = {},
        draft: (String) -> Unit = {},
        add: (Boolean) -> Unit = {},
        accept: () -> Unit = {},
        closeGroup: () -> Unit = {},
        confirm: () -> Unit = {},
        height: Int = 550,
    ) {
        LegadoComposeTheme {
            BookImportScreen(
                state,
                {},
                toggle,
                {},
                code,
                expand,
                menu,
                {},
                draft,
                add,
                accept,
                closeGroup,
                confirm,
                {},
                {},
                Modifier.height(height.dp),
            )
        }
    }
}
