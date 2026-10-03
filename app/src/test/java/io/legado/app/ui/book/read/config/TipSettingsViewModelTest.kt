package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipSetting
import org.junit.Assert.*
import org.junit.Test

class TipSettingsViewModelTest {
    @Test
    fun settingsClampToControlRangesAndRepeatedValuesDoNotWrite() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        val ranges =
            mapOf(
                TipSetting.TitleMode to (0..3),
                TipSetting.TitleSize to (0..20),
                TipSetting.TitleNumberSize to (0..20),
                TipSetting.TitleLineSpacing to (-20..30),
                TipSetting.TitleNumberSpacing to (-50..100),
                TipSetting.TitleTop to (0..100),
                TipSetting.TitleBottom to (0..100),
                TipSetting.TitleBold to (-1..2),
                TipSetting.HeaderMode to (0..2),
                TipSetting.FooterMode to (0..1),
                TipSetting.SplitTitle to (0..1),
                TipSetting.TipSize to (5..50),
            )
        ranges.forEach { (setting, range) ->
            model.set(setting, 1000)
            assertEquals(range.last, model.state.value.settings[setting])
            val count = repository.settingsWritten.size
            model.set(setting, 1000)
            assertEquals(count, repository.settingsWritten.size)
            model.set(setting, -1000)
            assertEquals(range.first, model.state.value.settings[setting])
        }
    }

    @Test
    fun eachColorTargetSavesOpaqueRgbAndCustomBlackNeverMeansFollowText() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        listOf(
                TipSetting.TitleColor,
                TipSetting.TitleNumberColor,
                TipSetting.TipColor,
                TipSetting.DividerColor,
            )
            .forEach { target ->
                model.openColor(target)
                model.editColor("000000")
                model.confirmColor()
                model.confirmColor()
                assertEquals(target to 0xff000000.toInt(), repository.settingsWritten.last())
            }
        assertEquals(4, repository.settingsWritten.size)
    }

    @Test
    fun invalidColorCannotSaveAndCancellingDoesNotWrite() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.openColor(TipSetting.TitleColor)
        model.editColor("ZZ0000")
        assertNull(model.parsedColor())
        model.confirmColor()
        assertNotNull(model.state.value.color)
        assertTrue(repository.settingsWritten.isEmpty())
        model.editColor("12")
        assertNull(model.parsedColor())
        model.dismissEditors()
        assertTrue(repository.settingsWritten.isEmpty())
    }

    @Test
    fun colorChannelsAndHexInputRetainOtherChannels() {
        val model = TipSettingsViewModel(FakeTipSettingsRepository(), SavedStateHandle())
        model.openColor(TipSetting.TipColor)
        model.editColor("#112233")
        model.setColorChannel(1, 255)
        assertEquals("11FF33", model.state.value.color?.hex)
        model.setColorChannel(2, -1)
        assertEquals("11FF00", model.state.value.color?.hex)
        model.setColorChannel(0, 999)
        assertEquals("FFFF00", model.state.value.color?.hex)
        model.setColorChannel(3, 1)
        assertEquals("FFFF00", model.state.value.color?.hex)
    }

    @Test
    fun restoredColorDraftKeepsExactInputAndDoesNotChangeOnExternalRefresh() {
        val repository = FakeTipSettingsRepository()
        val handle = SavedStateHandle()
        val original = TipSettingsViewModel(repository, handle)
        original.openColor(TipSetting.TitleNumberColor)
        original.editColor("aB12")
        val restored =
            TipSettingsViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        restored.refresh()
        assertEquals(original.state.value.color, restored.state.value.color)
        assertTrue(repository.settingsWritten.isEmpty())
    }

    @Test
    fun selectorRestoresAndDividerSpecialValuesRemainDistinct() {
        val repository = FakeTipSettingsRepository()
        val handle = SavedStateHandle()
        val model = TipSettingsViewModel(repository, handle)
        model.openSelector(TipSetting.DividerColor)
        val restored =
            TipSettingsViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(TipSetting.DividerColor, restored.state.value.selector)
        restored.select(-1)
        assertEquals(TipSetting.DividerColor to -1, repository.settingsWritten.last())
        assertNull(restored.state.value.selector)
        restored.openSelector(TipSetting.DividerColor)
        restored.select(0)
        assertEquals(TipSetting.DividerColor to 0, repository.settingsWritten.last())
    }

    @Test
    fun fontSelectionPreservesLegacyEmptyDefaultRefreshBehavior() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.setFont("font.ttf")
        model.setFont("font.ttf")
        model.setFont("")
        model.setFont("")
        assertEquals(listOf("font.ttf", "", ""), repository.fontsWritten)
        assertEquals("", model.state.value.settings.titleFont)
    }
}
