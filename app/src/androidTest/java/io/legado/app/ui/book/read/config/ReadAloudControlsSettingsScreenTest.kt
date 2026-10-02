package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadAloudControlsSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Repository : ReadAloudControlsSettingsRepository {
        var settings = ReadAloudControlsSettings(ReadAloudControlsToggle.entries.associateWith { it.defaultValue },
            ReadAloudControlsNumber.entries.associateWith { it.defaultValue })
        override fun load() = settings
        override fun setToggle(setting: ReadAloudControlsToggle, enabled: Boolean) { settings = settings.copy(toggles = settings.toggles + (setting to enabled)) }
        override fun setNumber(setting: ReadAloudControlsNumber, value: Int) { settings = settings.copy(numbers = settings.numbers + (setting to value)) }
        override fun observe(onChange: () -> Unit) = AutoCloseable { }
    }
    private fun show(repository: Repository = Repository(), action: (ReadAloudControlsAction) -> Boolean = { true }): ReadAloudControlsSettingsViewModel {
        val model = ReadAloudControlsSettingsViewModel(repository, SavedStateHandle())
        compose.setContent { LegadoComposeTheme {
            ReadAloudControlsSettingsRoute(model, Color.White, action, Modifier.height(550.dp))
        } }
        return model
    }
    @Test fun switchesExposeIndependentDefaultsAndPersistUserChoices() {
        val repository = Repository()
        show(repository)
        ReadAloudControlsToggle.entries.forEach {
            val row = compose.onNodeWithTag("aloud-controls-switch-${it.key}").performScrollTo().assertHeightIsAtLeast(48.dp)
            if (it.defaultValue) row.assertIsOn() else row.assertIsOff()
            row.performClick()
        }
        compose.runOnIdle { ReadAloudControlsToggle.entries.forEach { assertEquals(!it.defaultValue, repository.settings[it]) } }
    }
    @Test fun microAdjustmentButtonsUseWidthOneOpacityFiveAndThresholdTen() {
        val repository = Repository()
        show(repository)
        ReadAloudControlsNumber.entries.forEach {
            compose.onNodeWithTag("aloud-controls-plus-${it.name}").performScrollTo().performClick()
            compose.onNodeWithTag("aloud-controls-value-${it.name}").assertTextEquals((it.defaultValue + it.increment).toString())
        }
        compose.runOnIdle { assertEquals(95, repository.settings[ReadAloudControlsNumber.Opacity]); assertEquals(110, repository.settings[ReadAloudControlsNumber.Threshold]) }
    }
    @Test fun sliderSemanticsCanSetArbitraryValidOpacityAndDisableButtonsAtBounds() {
        val repository = Repository()
        val model = show(repository)
        compose.onNodeWithTag("aloud-controls-slider-Opacity").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(37f)) }
        compose.runOnIdle { model.finish(ReadAloudControlsNumber.Opacity) }
        compose.onNodeWithTag("aloud-controls-value-Opacity").assertTextEquals("37")
        compose.runOnIdle { model.drag(ReadAloudControlsNumber.Opacity, 0); model.finish(ReadAloudControlsNumber.Opacity) }
        compose.onNodeWithTag("aloud-controls-minus-Opacity").assertIsNotEnabled()
    }
    @Test fun revealAndResetPositionRowsEmitTheirExactActions() {
        val actions = mutableListOf<ReadAloudControlsAction>()
        show(action = { actions += it; true })
        compose.onNodeWithTag("aloud-controls-reveal").performScrollTo().performClick()
        compose.onNodeWithTag("aloud-controls-reset-position").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(ReadAloudControlsAction.Reveal, ReadAloudControlsAction.ResetPosition), actions) }
    }
}
