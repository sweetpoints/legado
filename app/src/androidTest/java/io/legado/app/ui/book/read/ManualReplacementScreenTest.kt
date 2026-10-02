package io.legado.app.ui.book.read

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ManualReplacementScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: ManualReplacementViewModel
    private fun setup(rows: List<ManualReplacementRow> = (1L..4L).map { ManualReplacementRow(it, "Same") }, selected: List<Long> = emptyList()) {
        compose.runOnIdle { model = ManualReplacementViewModel(object : ManualReplacementRepository {
            override suspend fun candidates(source: Boolean) = rows
        }, SavedStateHandle(), true, selected) }
        compose.setContent { LegadoComposeTheme {
            val state by model.state.collectAsState()
            ManualReplacementScreen(state, model::toggle, model::all, model::confirm, model::cancel, model::load,
                model::beginRange, model::moveRange, model::endRange, model::cancelRange, Modifier.width(360.dp))
        } }
    }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnIdle { model.stop() } }
    @Test fun duplicateNamesSelectByIdAndCheckboxIsAccessible() {
        setup(); compose.onNodeWithTag("manual-rule-2").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("manual-rule-1").assertIsOff()
        compose.onNodeWithTag("manual-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(2L), model.consumeConfirmation()); assertNull(model.consumeConfirmation()) }
    }
    @Test fun allCanBeClearedAndCancellationDoesNotEmitAnyConfirmedResult() {
        setup(); compose.onNodeWithTag("manual-all").performClick()
        (1..4).forEach { compose.onNodeWithTag("manual-rule-$it").assertIsOn() }
        compose.onNodeWithTag("manual-all").performClick(); compose.onNodeWithTag("manual-rule-1").assertIsOff()
        compose.onNodeWithTag("manual-rule-1").performClick(); compose.onNodeWithTag("manual-cancel").performClick()
        compose.runOnIdle { assertTrue(model.state.value.finished); assertNull(model.consumeConfirmation()) }
    }
    @Test fun slideSelectionReversesRangeAndConsumesReleaseWithoutTogglingTheRowAgain() {
        setup(selected = listOf(4)); val list = compose.onNodeWithTag("manual-rule-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("manual-rule-1").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("manual-rule-3").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("manual-rule-list").performTouchInput {
            val x = width * 32f / 360f
            down(Offset(x, first.center.y - list.top)); moveTo(Offset(x, third.center.y - list.top))
            moveTo(Offset(x, first.center.y - list.top)); up()
        }
        compose.runOnIdle { assertEquals(setOf(1L, 4L), model.state.value.selected) }
        compose.onNodeWithTag("manual-rule-1").assertIsOn(); compose.onNodeWithTag("manual-rule-2").assertIsOff()
    }
    @Test fun canceledSlideRestoresDraftAndNeverAddsSelectionToTheSavedState() {
        setup(selected = listOf(4)); val list = compose.onNodeWithTag("manual-rule-list").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("manual-rule-1").fetchSemanticsNode().boundsInRoot
        val third = compose.onNodeWithTag("manual-rule-3").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("manual-rule-list").performTouchInput {
            val x = width * 32f / 360f
            down(Offset(x, first.center.y - list.top)); moveTo(Offset(x, third.center.y - list.top)); cancel()
        }
        compose.runOnIdle { assertEquals(setOf(4L), model.state.value.selected) }
    }
    @Test fun confirmedSelectionWaitsForResumedHostAndDoesNotReplayAfterAnotherResume() {
        lateinit var owner: LifecycleOwner; lateinit var registry: LifecycleRegistry
        var deliveries = 0; var closes = 0; var selected = emptyList<Long>()
        compose.runOnIdle {
            owner = object : LifecycleOwner { override val lifecycle: Lifecycle get() = registry }
            registry = LifecycleRegistry(owner); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            model = ManualReplacementViewModel(object : ManualReplacementRepository {
                override suspend fun candidates(source: Boolean) = listOf(ManualReplacementRow(7, "Rule"))
            }, SavedStateHandle(), true, listOf(7))
        }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            ManualReplacementRoute(model, { true }, { selected = it; deliveries++ }, { closes++ })
        } } }
        compose.runOnIdle { model.confirm(); assertEquals(0, deliveries); registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        compose.runOnIdle { assertEquals(0, deliveries); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitUntil { deliveries == 1 }
        compose.runOnIdle {
            assertEquals(listOf(7L), selected); assertEquals(1, closes)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.runOnIdle { assertEquals(1, deliveries) }
    }
    @Test fun emptyListStillAllowsAnExplicitEmptyConfirmation() {
        setup(emptyList()); compose.onNodeWithTag("manual-confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(emptyList<Long>(), model.consumeConfirmation()) }
    }
}
