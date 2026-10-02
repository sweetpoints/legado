package io.legado.app.ui.book.read.config

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipSetting
import io.legado.app.data.preferences.TipSettingsRepository
import io.legado.app.data.preferences.TipSettingsSnapshot
import io.legado.app.data.preferences.TipTemplateSlot
import io.legado.app.help.config.ReaderInfoTemplate
import io.legado.app.ui.theme.LegadoComposeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TipSettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private class Repository : TipSettingsRepository {
        var settings = TipSettingsSnapshot(TipSetting.entries.associateWith {
            when (it) { TipSetting.TipSize -> 12; TipSetting.TitleBold -> -1; else -> 0 }
        }, templates = TipTemplateSlot.entries.associateWith { "old" })
        var templateWrites = 0
        override fun load() = settings
        override fun set(setting: TipSetting, value: Int) { settings = settings.copy(values = settings.values + (setting to value)) }
        override fun setFont(path: String) { settings = settings.copy(titleFont = path) }
        override fun setTemplate(slot: TipTemplateSlot, value: String) {
            templateWrites++
            settings = settings.copy(templates = settings.templates + (slot to value))
        }
    }
    private fun show(repository: Repository = Repository()): TipSettingsViewModel {
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        compose.setContent { LegadoComposeTheme { TipSettingsRoute(model, {}, Modifier.heightIn(max = 500.dp)) } }
        return model
    }

    @Test fun spacingAndTextSizeShowActualUnitsRatherThanSeekProgress() {
        val model = show()
        compose.runOnIdle {
            model.set(TipSetting.TitleLineSpacing, -20)
            model.set(TipSetting.SplitTitle, 1)
            model.set(TipSetting.TitleNumberSpacing, -50)
            model.set(TipSetting.TipSize, 12)
        }
        compose.onNodeWithTag("tip-value-TitleLineSpacing").performScrollTo().assertTextEquals("-2.0")
        compose.onNodeWithTag("tip-value-TitleNumberSpacing").performScrollTo().assertTextEquals("-50")
        compose.onNodeWithTag("tip-value-TipSize").performScrollTo().assertTextEquals("12")
    }

    @Test fun preciseSliderButtonsChangeOneStepAndDisableAtBounds() {
        val model = show()
        compose.onNodeWithTag("tip-minus-TitleSize").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("tip-plus-TitleSize").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, model.state.value.settings[TipSetting.TitleSize]) }
        compose.onNodeWithTag("tip-minus-TitleSize").performScrollTo().performClick()
        compose.runOnIdle { model.set(TipSetting.TitleSize, 20) }
        compose.onNodeWithTag("tip-plus-TitleSize").performScrollTo().assertIsNotEnabled()
    }

    @Test fun splitTitleShowsAndHidesNumberStylingControls() {
        val model = show()
        compose.onNodeWithTag("tip-slider-TitleNumberSize").assertDoesNotExist()
        compose.onNodeWithTag("tip-split-title").performScrollTo().performClick()
        compose.onNodeWithTag("tip-slider-TitleNumberSize").assertExists()
        compose.onNodeWithTag("tip-slider-TitleNumberSpacing").assertExists()
        compose.onNodeWithTag("tip-number-color").assertExists()
        compose.runOnIdle { assertEquals(1, model.state.value.settings[TipSetting.SplitTitle]) }
        compose.onNodeWithTag("tip-split-title").performScrollTo().performClick()
        compose.onNodeWithTag("tip-number-color").assertDoesNotExist()
    }

    @Test fun templateTypingChipInsertionAndConfirmPersistExactSelectedSlot() {
        val repository = Repository()
        show(repository)
        compose.onNodeWithTag("tip-template-FooterRight").performScrollTo().performClick()
        compose.onNodeWithTag("tip-template-editor").performTextReplacement("draft")
        compose.onNodeWithTag("tip-placeholder-${ReaderInfoTemplate.TIME}").performScrollTo().performClick()
        compose.onNodeWithTag("tip-template-editor").assertTextContains("draft${ReaderInfoTemplate.TIME}")
        compose.onNodeWithTag("tip-template-confirm").performClick()
        compose.runOnIdle {
            assertEquals("draft${ReaderInfoTemplate.TIME}", repository.settings.templates[TipTemplateSlot.FooterRight])
            assertEquals("old", repository.settings.templates[TipTemplateSlot.HeaderLeft])
            assertEquals(1, repository.templateWrites)
        }
        compose.onNodeWithTag("tip-template-editor").assertDoesNotExist()
    }

    @Test fun templateCancelDoesNotWriteAndAllPlaceholdersAreAvailable() {
        val repository = Repository()
        show(repository)
        compose.onNodeWithTag("tip-template-HeaderLeft").performScrollTo().performClick()
        ReaderInfoTemplate.placeholders.forEach { compose.onNodeWithTag("tip-placeholder-$it").assertExists() }
        compose.onNodeWithTag("tip-template-editor").performTextReplacement("discard")
        compose.onNodeWithTag("tip-template-cancel").performClick()
        compose.runOnIdle { assertEquals(0, repository.templateWrites) }
        compose.onNodeWithTag("tip-template-HeaderLeft").performScrollTo().performClick()
        compose.onNodeWithTag("tip-template-editor").assertTextContains("old")
    }

    @Test fun composeColorPickerValidatesAndConfirmsOpaqueCustomColor() {
        val model = show()
        compose.onNodeWithTag("tip-title-color").performScrollTo().performClick()
        compose.onNodeWithTag("tip-select-1").performClick()
        compose.onNodeWithTag("tip-color-hex").performTextReplacement("zz")
        compose.onNodeWithTag("tip-color-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("tip-color-hex").performTextReplacement("112233")
        compose.onNodeWithTag("tip-color-confirm").performClick()
        compose.runOnIdle { assertEquals(0xff112233.toInt(), model.state.value.settings[TipSetting.TitleColor]) }
        compose.onNodeWithTag("tip-color-hex").assertDoesNotExist()
    }

    @Test fun headerFooterAndWeightSelectorsUseMatchingModes() {
        val model = show()
        compose.onNodeWithTag("tip-title-weight").performScrollTo().performClick()
        compose.onNodeWithTag("tip-select-3").performClick()
        compose.onNodeWithTag("tip-header-mode").performScrollTo().performClick()
        compose.onNodeWithTag("tip-select-2").performClick()
        compose.onNodeWithTag("tip-footer-mode").performScrollTo().performClick()
        compose.onNodeWithTag("tip-select-1").performClick()
        compose.runOnIdle {
            assertEquals(2, model.state.value.settings[TipSetting.TitleBold])
            assertEquals(2, model.state.value.settings[TipSetting.HeaderMode])
            assertEquals(1, model.state.value.settings[TipSetting.FooterMode])
        }
    }
}
