package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadStyleSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun initial() = ReadStyleSettingsState(ReadStyleSettingsSnapshot((0..6).map { ReadStylePreset(it, "Style $it", 0xff000000.toInt(), "{}") },
        0, false, 15, 50, 22, 10, "", 0, 0, 2, 3))
    @Composable private fun Content(state: ReadStyleSettingsState, change: (ReadStyleSettingsState) -> Unit,
        slider: (ReadStyleSlider, Int) -> Unit = { _, _ -> }, preset: (Int) -> Unit = {}, edit: (Int) -> Unit = {},
        add: () -> Unit = {}, animation: (Int) -> Unit = {}, pick: (Int) -> Unit = {}, open: (ReadStyleDestination) -> Unit = {}) {
        ReadStyleSettingsScreen(state, slider, preset, edit, add, { change(state.copy(settings = state.settings.copy(shared = it))) }, animation,
            { change(state.copy(picker = it)) }, pick, open, Color.White, Color.Black, Modifier.width(360.dp).heightIn(max = 600.dp),
            preview = { _, modifier -> Box(modifier) })
    }
    @Test fun reflectingPresetAndSharedLayoutSelectionNeverReplaysAnimationCallback() {
        var state by mutableStateOf(initial()); val callbacks = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { Content(state, { state = it }, animation = { callbacks += it }) } }
        compose.onNodeWithTag("read-style-animation-3").performScrollTo().assertIsSelected()
        compose.runOnIdle { state = state.copy(settings = state.settings.copy(selected = 2, shared = true, pageAnimation = 4)) }
        compose.onNodeWithTag("read-style-animation-4").performScrollTo().assertIsSelected()
        compose.runOnIdle { assertTrue(callbacks.isEmpty()) }
        compose.onNodeWithTag("read-style-animation-0").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(0), callbacks) }
    }
    @Test fun slidersHaveOriginalDisplayAccessibleRangeAndOneStepEndpointButtons() {
        var state by mutableStateOf(initial()); val changes = mutableListOf<Pair<ReadStyleSlider, Int>>()
        compose.setContent { LegadoComposeTheme { Content(state, { state = it }, slider = { slider, value ->
            changes += slider to value
            val settings = when (slider) { ReadStyleSlider.TextSize -> state.settings.copy(textSize = value); ReadStyleSlider.LetterSpacing -> state.settings.copy(letterSpacing = value)
                ReadStyleSlider.LineSpacing -> state.settings.copy(lineSpacing = value); ReadStyleSlider.ParagraphSpacing -> state.settings.copy(paragraphSpacing = value) }
            state = state.copy(settings = settings)
        }) } }
        compose.onNodeWithTag("read-style-value-LineSpacing").performScrollTo().assertTextEquals("0.2")
        compose.onNodeWithTag("read-style-plus-LineSpacing").performScrollTo().performClick()
        compose.onNodeWithTag("read-style-value-LineSpacing").assertTextEquals("0.3")
        compose.onNodeWithTag("read-style-slider-LineSpacing").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithTag("read-style-minus-LineSpacing").assertIsNotEnabled()
        compose.onNodeWithTag("read-style-value-LineSpacing").assertTextEquals("-2.0")
        compose.onNodeWithTag("read-style-slider-LineSpacing").performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithTag("read-style-plus-LineSpacing").assertIsNotEnabled()
        compose.onNodeWithTag("read-style-value-LineSpacing").assertTextEquals("3.0")
        compose.runOnIdle { assertEquals(ReadStyleSlider.LineSpacing to 23, changes.first()) }
    }
    @Test fun presetsClickLongClickAddAndSharedCheckboxKeepSeparateActions() {
        var state by mutableStateOf(initial()); val selected = mutableListOf<Int>(); val edited = mutableListOf<Int>(); var added = 0
        compose.setContent { LegadoComposeTheme { Content(state, { state = it }, preset = { selected += it }, edit = { edited += it }, add = { added++ }) } }
        compose.onNodeWithTag("read-style-presets").performScrollTo()
        compose.onNodeWithTag("read-style-preset-0").assertIsSelected().performClick()
        compose.onNodeWithTag("read-style-preset-1").performTouchInput { longClick() }
        compose.onNodeWithTag("read-style-presets").performScrollToNode(hasTestTag("read-style-add"))
        compose.onNodeWithTag("read-style-add").performClick()
        compose.onNodeWithTag("read-style-share").performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(listOf(0), selected); assertEquals(listOf(1), edited); assertEquals(1, added); assertTrue(state.settings.shared) }
    }
    @Test fun allFiveIndentChoicesRemainAndPickerCancelDoesNotCommit() {
        var state by mutableStateOf(initial()); val picked = mutableListOf<Int>()
        compose.setContent { LegadoComposeTheme { Content(state, { state = it }, pick = { picked += it; state = state.copy(picker = null) }) } }
        compose.onNodeWithTag("read-style-indent").performScrollTo().performClick()
        compose.onNodeWithTag("read-style-pick-4").assertExists()
        compose.onNodeWithTag("read-style-picker-cancel").performClick()
        compose.runOnIdle { assertTrue(picked.isEmpty()) }
        compose.onNodeWithTag("read-style-indent").performScrollTo().performClick()
        compose.onNodeWithTag("read-style-pick-4").performClick()
        compose.runOnIdle { assertEquals(listOf(4), picked) }
    }
    @Test fun horizontalActionsScrollToTipAndFontAndRestorationDoesNotInvokeCallbacks() {
        var state by mutableStateOf(initial()); val destinations = mutableListOf<ReadStyleDestination>()
        val restore = StateRestorationTester(compose)
        restore.setContent { LegadoComposeTheme { Content(state, { state = it }, open = { destinations += it }) } }
        compose.onNodeWithTag("read-style-tip").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("read-style-font").performScrollTo().performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(listOf(ReadStyleDestination.Tip, ReadStyleDestination.Font), destinations) }
    }
}
