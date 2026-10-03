package io.legado.app.ui.main.bookshelf.style1

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.legado.app.R
import io.legado.app.ui.main.bookshelf.components.BookshelfBookCardModel
import io.legado.app.ui.main.bookshelf.components.BookshelfHeaderModel
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookshelfHomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun initial() =
        BookshelfHomeState(
            listOf(
                BookshelfHomeGroup(1, "One", 0, true, false),
                BookshelfHomeGroup(2, "Two", 5, false, true),
            ),
            selectedId = 1,
            loading = false,
        )

    @Test
    fun tabsReselectLongClickSwipeAndRestoreSelectedPage() {
        var state by mutableStateOf(initial())
        val reselect = mutableListOf<Long>()
        val info = mutableListOf<Long>()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            LegadoComposeTheme {
                BookshelfHomeScreen(
                    state,
                    { state = state.copy(selectedId = it) },
                    { reselect += it },
                    { info += it },
                    {},
                    {},
                    {},
                    {},
                ) { group, _, active, modifier ->
                    Box(modifier.testTag("home-page-${group.id}")) {
                        if (active) Text("active-${group.id}")
                    }
                }
            }
        }
        compose.onNodeWithTag("shelf-tab-1").performClick()
        compose.onNodeWithTag("shelf-tab-2").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(1L), reselect)
            assertEquals(listOf(2L), info)
        }
        compose.onNodeWithTag("shelf-tab-2").performClick()
        compose.onNodeWithText("active-2").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("active-2").assertIsDisplayed()
        compose.onNodeWithTag("shelf-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("active-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1L, state.selectedId) }
    }

    @Test
    fun selectedSavedIdentityIsNotResetByInitialPagerSnapshotAndMetadataReorder() {
        var state by mutableStateOf(initial().copy(selectedId = 2))
        compose.setContent {
            LegadoComposeTheme {
                BookshelfHomeScreen(
                    state,
                    { state = state.copy(selectedId = it) },
                    {},
                    {},
                    {},
                    {},
                    {},
                    {},
                ) { group, _, active, modifier ->
                    Box(modifier) { if (active) Text("active-${group.id}") }
                }
            }
        }
        compose.onNodeWithText("active-2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2L, state.selectedId)
            state = state.copy(groups = state.groups.reversed())
        }
        compose.onNodeWithText("active-2").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2L, state.selectedId) }
    }

    @Test
    fun toolbarMenuPreservesAllActionsAndHeaderHasBothReaderAndInfoActions() {
        val menus = mutableListOf<Int>()
        var recent = 0
        var info = 0
        val state =
            initial()
                .copy(
                    header =
                        BookshelfHeaderModel(
                            7 to 2,
                            BookshelfBookCardModel(
                                "recent",
                                "Recent",
                                "Author",
                                "Chapter",
                                "Latest",
                                readProgress = .5f,
                            ),
                        )
                )
        compose.setContent {
            LegadoComposeTheme {
                BookshelfHomeScreen(
                    state,
                    {},
                    {},
                    {},
                    { menus += it },
                    { recent++ },
                    { info++ },
                    {},
                ) { _, _, _, modifier ->
                    Box(modifier)
                }
            }
        }
        compose.onNodeWithTag("shelf-search").performClick()
        compose.onNodeWithTag("shelf-continue").performClick()
        compose.onNodeWithTag("shelf-continue").performTouchInput { longClick() }
        val actions =
            listOf(
                R.id.menu_update_toc,
                R.id.menu_add_local,
                R.id.menu_remote,
                R.id.menu_add_url,
                R.id.menu_bookshelf_manage,
                R.id.menu_download,
                R.id.menu_group_manage,
                R.id.menu_bookshelf_layout,
                R.id.menu_export_bookshelf,
                R.id.menu_import_bookshelf,
                R.id.menu_log,
            )
        actions.forEach { id ->
            compose.onNodeWithTag("shelf-menu").performClick()
            compose.onNodeWithTag("shelf-menu-$id").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(R.id.menu_search) + actions, menus)
            assertEquals(1, recent)
            assertEquals(1, info)
        }
    }
}
