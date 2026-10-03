package io.legado.app.ui.highlight

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.*
import org.junit.Assert.*

class HighlightManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<HighlightManagementViewModel>()

    @After
    fun after() {
        compose.runOnIdle { models.forEach { it.stop() } }
    }

    private fun show(rows: ManagedHighlights = ManagedHighlights()): HighlightManagementViewModel {
        lateinit var model: HighlightManagementViewModel
        compose.runOnIdle {
            model =
                HighlightManagementViewModel(SavedStateHandle(), rows, ManagedHighlightSessions())
            models += model
        }
        compose.setContent {
            LegadoComposeTheme {
                HighlightManagementRoute(
                    model,
                    ManagedHighlightTransfers(),
                    {},
                    { _, _, _ -> },
                    { error(it) },
                )
            }
        }
        compose.waitUntil {
            model.state.value.rowsReady && model.state.value.rules.size == rows.flow.value.size
        }
        return model
    }

    @Test
    fun emptyStateKeepsAddImportAndGroupsButDisablesSelectionActions() {
        val rows = ManagedHighlights()
        rows.flow.value = emptyList()
        show(rows)
        compose.onNodeWithTag("highlight-management-empty").assertIsDisplayed()
        compose.onNodeWithTag("highlight-management-add").assertIsEnabled()
        compose.onNodeWithTag("highlight-management-selection-menu").assertIsNotEnabled()
        compose.onNodeWithTag("highlight-management-menu").performClick()
        compose.onNodeWithTag("highlight-management-import").assertIsDisplayed()
        compose.onNodeWithTag("highlight-management-groups").assertIsDisplayed()
    }

    @Test
    fun rowMenusPreserveTopBottomDeleteOrderAndRequireConfirmation() {
        val rows = ManagedHighlights()
        show(rows)
        compose.onNodeWithTag("highlight-management-more-a").performClick()
        val top =
            compose.onNodeWithTag("highlight-management-top-a").fetchSemanticsNode().boundsInRoot
        val bottom =
            compose.onNodeWithTag("highlight-management-bottom-a").fetchSemanticsNode().boundsInRoot
        val delete =
            compose.onNodeWithTag("highlight-management-delete-a").fetchSemanticsNode().boundsInRoot
        assertTrue(top.top < bottom.top && bottom.top < delete.top)
        compose.onNodeWithTag("highlight-management-delete-a").performClick()
        assertTrue(rows.deleted.isEmpty())
        compose.onNodeWithTag("highlight-management-delete-confirm").performClick()
        compose.waitUntil { rows.deleted.size == 1 }
        assertEquals(setOf("a"), rows.deleted.single())
    }

    @Test
    fun checkboxSwitchAndSelectionMenuKeepActualEnableAndDeleteBehavior() {
        val rows = ManagedHighlights()
        val model = show(rows)
        compose.onNodeWithTag("highlight-management-enabled-a").performClick()
        compose.waitUntil { rows.enabled.size == 1 }
        assertEquals(setOf("a") to false, rows.enabled.single())
        compose.onNodeWithTag("highlight-management-select-b").performClick().assertIsOn()
        compose.onNodeWithTag("highlight-management-selection-menu").performClick()
        compose.onNodeWithTag("highlight-management-disable-selection").performClick()
        compose.waitUntil { rows.enabled.size == 2 }
        assertEquals(setOf("b") to false, rows.enabled.last())
        compose.onNodeWithTag("highlight-management-delete-selection").performClick()
        assertEquals(setOf("b"), model.state.value.draft.deletion)
    }

    @Test
    fun actualDragTouchCancelRestoresBaselineAndReleaseCommits() {
        val rows = ManagedHighlights()
        val model = show(rows)
        val a =
            compose.onNodeWithTag("highlight-management-name-a").fetchSemanticsNode().boundsInRoot
        val c =
            compose.onNodeWithTag("highlight-management-name-c").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("highlight-management-name-a").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, c.center.y - a.center.y), 400)
            cancel()
        }
        compose.runOnIdle {
            assertEquals(listOf("a", "b", "c"), model.state.value.rules.map { it.uuid })
            assertTrue(rows.reordered.isEmpty())
        }
        compose.onNodeWithTag("highlight-management-name-a").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, c.center.y - a.center.y), 400)
            up()
        }
        compose.waitUntil { rows.reordered.isNotEmpty() }
        assertEquals(listOf("b", "c", "a"), rows.reordered.single())
    }

    @Test
    fun actualSlideCancelRestoresSelectionAndReleaseTogglesRange() {
        val model = show()
        compose.onNodeWithTag("highlight-management-select-a").performClick()
        val a =
            compose.onNodeWithTag("highlight-management-row-a").fetchSemanticsNode().boundsInRoot
        val c =
            compose.onNodeWithTag("highlight-management-row-c").fetchSemanticsNode().boundsInRoot
        val listTop =
            compose.onNodeWithTag("highlight-management-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("highlight-management-list").performTouchInput {
            down(Offset(24f, a.center.y - listTop))
            moveBy(Offset(0f, c.center.y - a.center.y), 500)
            cancel()
        }
        compose.runOnIdle { assertEquals(setOf("a"), model.state.value.draft.selection) }
        compose.onNodeWithTag("highlight-management-list").performTouchInput {
            down(Offset(24f, a.center.y - listTop))
            moveBy(Offset(0f, c.center.y - a.center.y), 500)
            up()
        }
        compose.waitUntil { model.state.value.draft.selection == setOf("b", "c") }
    }

    @Test
    fun accessibilityMoveCommitsSameReorderingAsPointerRelease() {
        val rows = ManagedHighlights()
        show(rows)
        val actions =
            compose
                .onNodeWithTag("highlight-management-name-a")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.single().action()) }
        compose.waitUntil { rows.reordered.isNotEmpty() }
        assertEquals(listOf("b", "a", "c"), rows.reordered.single())
    }

    @Test
    fun groupChooserAndListScrollRestoreWithoutRawGroupInScreenSavedState() {
        val tester = StateRestorationTester(compose)
        val state =
            HighlightManagementState(
                rules = (0..100).map { managedHighlight("$it", it, "Characters") },
                groups = listOf("Characters"),
                loaded = true,
                loading = false,
            )
        tester.setContent {
            LegadoComposeTheme { HighlightManagementScreen(state, HighlightManagementActions()) }
        }
        compose
            .onNodeWithTag("highlight-management-list")
            .performScrollToNode(hasTestTag("highlight-management-row-40"))
        compose.onNodeWithTag("highlight-management-menu").performClick()
        compose.onNodeWithTag("highlight-management-filter").performClick()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("highlight-filter-Characters").assertIsDisplayed()
        compose.onNodeWithTag("highlight-management-filter-cancel").performClick()
        compose.onNodeWithTag("highlight-management-row-40").assertIsDisplayed()
    }

    @Test
    fun darkSmallScreenKeepsReadableNamesAndAccessibleActions() {
        val state =
            HighlightManagementState(
                rules = listOf(managedHighlight("a")),
                loaded = true,
                loading = false,
            )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(320.dp, 300.dp)) {
                    HighlightManagementScreen(state, HighlightManagementActions())
                }
            }
        }
        compose.onNodeWithTag("highlight-management-name-a").assertIsDisplayed()
        val image =
            compose.onNodeWithTag("highlight-management-name-a").captureToImage().toPixelMap()
        assertTrue(
            (0 until image.width).any { x ->
                (0 until image.height).any { y ->
                    val c = image[x, y]
                    c.red > .7f && c.green > .7f && c.blue > .7f
                }
            }
        )
        compose.onNodeWithTag("highlight-management-more-a").performClick()
        compose.onNodeWithTag("highlight-management-delete-a").assertIsDisplayed()
    }
}
