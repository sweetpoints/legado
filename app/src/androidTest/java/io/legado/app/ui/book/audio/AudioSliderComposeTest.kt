package io.legado.app.ui.book.audio

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AudioSliderComposeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun accessibleSliderChangesSpeedAndLiveLabelWithoutProgrammaticDispatch() {
        val changed = mutableListOf<Float>(); val model = AudioSliderController(AudioSliderMode.Speed) { changed += it }
        model.refresh(1.2f)
        compose.setContent { LegadoComposeTheme { val state by model.state.collectAsStateWithLifecycle(); AudioSliderScreen(state, model::user) } }
        compose.onNodeWithTag("audio-slider-value").assertTextEquals("1.2X")
        compose.onNodeWithTag("audio-slider-control").performSemanticsAction(SemanticsActions.SetProgress) { it(1.8f) }
        compose.onNodeWithTag("audio-slider-value").assertTextEquals("1.8X"); assertEquals(listOf(1.8f), changed)
        compose.runOnIdle { model.refresh(2f) }; compose.onNodeWithTag("audio-slider-value").assertTextEquals("2.0X"); assertEquals(1, changed.size)
    }
    @Test fun timerSliderKeepsIntegerMinutesAndBoundedSemanticRange() {
        val changed = mutableListOf<Float>(); val model = AudioSliderController(AudioSliderMode.Timer) { changed += it }
        compose.setContent { LegadoComposeTheme { val state by model.state.collectAsStateWithLifecycle(); AudioSliderScreen(state, model::user) } }
        compose.onNodeWithTag("audio-slider-control").performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
        assertEquals(listOf(17f), changed)
        compose.onNodeWithTag("audio-slider-control").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(17f, 0f..180f, 179))
    }
}
