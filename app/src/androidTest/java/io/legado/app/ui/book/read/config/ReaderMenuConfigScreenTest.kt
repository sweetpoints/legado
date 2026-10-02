package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.preferences.ReaderMenuSettingsRepository
import io.legado.app.help.ReaderMenuConfig
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderMenuConfigScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun state() = ReaderMenuConfigUiState(ReaderMenuConfig.ALL_KEYS.map { ReaderMenuEntry(it, true) })

    @Test fun togglingAndMenuActionsEmitExactIntents() {
        val actions = mutableListOf<ReaderMenuEditAction>()
        compose.setContent {
            LegadoComposeTheme { ReaderMenuConfigScreen(state(), { actions += it }, {}, Modifier.heightIn(max = 650.dp)) }
        }
        compose.onNodeWithTag("reader-menu-item-bookmark").assertIsOn().performClick()
        compose.onNodeWithTag("reader-menu-actions").performClick()
        compose.onNodeWithTag("reader-menu-select-none").performClick()
        compose.onNodeWithTag("reader-menu-actions").performClick()
        compose.onNodeWithTag("reader-menu-select-all").performClick()
        compose.onNodeWithTag("reader-menu-actions").performClick()
        compose.onNodeWithTag("reader-menu-reset").performClick()
        compose.runOnIdle { assertEquals(listOf(ReaderMenuEditAction.Toggle("bookmark", false),
            ReaderMenuEditAction.SetAll(false), ReaderMenuEditAction.SetAll(true), ReaderMenuEditAction.Reset), actions) }
    }

    @Test fun leftStripSlidesSelectionAndFinishesGesture() {
        val actions = mutableListOf<ReaderMenuEditAction>()
        compose.setContent {
            LegadoComposeTheme { ReaderMenuConfigScreen(state(), { actions += it }, {}, Modifier.heightIn(max = 650.dp)) }
        }
        val list = compose.onNodeWithTag("reader-menu-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("reader-menu-item-bookmark").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("reader-menu-item-highlightRule").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("reader-menu-list").performTouchInput {
            down(Offset(8f, first.center.y - list.top))
            moveTo(Offset(8f, second.center.y - list.top))
            up()
        }
        compose.runOnIdle {
            assertEquals(ReaderMenuEditAction.StartSelection("bookmark"), actions.first())
            assertTrue(actions.contains(ReaderMenuEditAction.SelectionTo("highlightRule")))
            assertEquals(ReaderMenuEditAction.FinishGesture(true), actions.last())
            assertTrue(actions.none { it is ReaderMenuEditAction.Toggle })
        }
    }

    @Test fun rightHandleDragsAndAccessibilityCanReorderWithoutTouch() {
        val actions = mutableListOf<ReaderMenuEditAction>()
        compose.setContent {
            LegadoComposeTheme { ReaderMenuConfigScreen(state(), { actions += it }, {}, Modifier.heightIn(max = 650.dp)) }
        }
        val list = compose.onNodeWithTag("reader-menu-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("reader-menu-drag-bookmark", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("reader-menu-item-highlightRule").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("reader-menu-list").performTouchInput {
            down(Offset(first.center.x - list.left, first.center.y - list.top))
            moveTo(Offset(first.center.x - list.left, second.center.y - list.top))
            up()
        }
        compose.runOnIdle {
            assertEquals(ReaderMenuEditAction.StartReorder("bookmark"), actions.first())
            assertTrue(actions.contains(ReaderMenuEditAction.Move("bookmark", "highlightRule")))
            assertTrue(actions.none { it is ReaderMenuEditAction.Toggle })
        }
        val semantics = compose.onNodeWithTag("reader-menu-item-bookmark").fetchSemanticsNode().config
        compose.runOnIdle {
            semantics[SemanticsActions.CustomActions].first().action()
            assertEquals(ReaderMenuEditAction.Step("bookmark", 1), actions.last())
        }
    }

    @Test fun moreCheckboxAndFailureRetryRemainReachable() {
        val actions = mutableListOf<ReaderMenuEditAction>()
        var state by mutableStateOf(ReaderMenuConfigUiState(listOf(ReaderMenuEntry("bookmark", false)), error = "save failed"))
        compose.setContent {
            LegadoComposeTheme { ReaderMenuConfigScreen(state, { actions += it }, {}, Modifier.heightIn(max = 650.dp)) }
        }
        compose.onNodeWithTag("reader-menu-item-bookmark").assertIsOff()
        compose.onNodeWithTag("reader-menu-retry").performClick()
        compose.runOnIdle { assertEquals(listOf(ReaderMenuEditAction.RetrySave), actions) }
    }
    @Test fun canceledSelectionAndReorderRestoreBaselineWithoutPersisting() {
        val base = ReaderMenuConfig(ReaderMenuConfig.ALL_KEYS, emptyList())
        var writes = 0
        val repository = object : ReaderMenuSettingsRepository {
            override fun load() = base
            override fun save(config: ReaderMenuConfig) { writes++ }
        }
        val model = ReaderMenuConfigViewModel(repository, SavedStateHandle())
        val baseline = model.state.value.entries
        val actions = mutableListOf<ReaderMenuEditAction>()
        compose.setContent {
            val state by model.state.collectAsStateWithLifecycle()
            LegadoComposeTheme { ReaderMenuConfigScreen(state, { actions += it; model.edit(it) }, {}, Modifier.heightIn(max = 650.dp)) }
        }
        listOf(false, true).forEach { reorder ->
            val list = compose.onNodeWithTag("reader-menu-list").fetchSemanticsNode().boundsInRoot
            val first = compose.onNodeWithTag("reader-menu-item-bookmark").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("reader-menu-item-highlightRule").fetchSemanticsNode().boundsInRoot
            val x = if (reorder) compose.onNodeWithTag("reader-menu-drag-bookmark", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot.center.x - list.left else 8f
            compose.onNodeWithTag("reader-menu-list").performTouchInput {
                down(Offset(x, first.center.y - list.top))
                moveTo(Offset(x, second.center.y - list.top))
                cancel()
            }
            compose.runOnIdle {
                assertEquals(ReaderMenuEditAction.FinishGesture(false), actions.last())
                assertEquals(baseline, model.state.value.entries)
                assertEquals(0, writes)
                assertEquals(0, model.state.value.refreshRequest)
                assertTrue(actions.none { it is ReaderMenuEditAction.Toggle })
            }
        }
    }

}
