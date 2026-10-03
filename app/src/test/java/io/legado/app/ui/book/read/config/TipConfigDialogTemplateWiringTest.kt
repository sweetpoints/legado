package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TipSetting
import io.legado.app.data.preferences.TipSettingsRepository
import io.legado.app.data.preferences.TipSettingsSnapshot
import io.legado.app.data.preferences.TipTemplateSlot
import io.legado.app.data.preferences.tipSettingEvents
import io.legado.app.data.preferences.tipTemplateEvents
import io.legado.app.help.config.ReaderInfoTemplate
import org.junit.Assert.*
import org.junit.Test

internal class FakeTipSettingsRepository : TipSettingsRepository {
    var snapshot =
        TipSettingsSnapshot(
            TipSetting.entries.associateWith { if (it == TipSetting.TipSize) 12 else 0 },
            templates = TipTemplateSlot.entries.associateWith { "prefix-${it.name}" },
        )
    val settingsWritten = mutableListOf<Pair<TipSetting, Int>>()
    val templatesWritten = mutableListOf<Pair<TipTemplateSlot, String>>()
    val fontsWritten = mutableListOf<String>()

    override fun load() = snapshot

    override fun set(setting: TipSetting, value: Int) {
        settingsWritten += setting to value
        snapshot = snapshot.copy(values = snapshot.values + (setting to value))
    }

    override fun setFont(path: String) {
        fontsWritten += path
        snapshot = snapshot.copy(titleFont = path)
    }

    override fun setTemplate(slot: TipTemplateSlot, value: String) {
        templatesWritten += slot to value
        snapshot = snapshot.copy(templates = snapshot.templates + (slot to value))
    }
}

class TipConfigDialogTemplateWiringTest {
    @Test
    fun eachOfSixSlotsOpensExactCurrentTemplateAndConfirmsOnlyItsOwnField() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        TipTemplateSlot.entries.forEach { slot ->
            model.openTemplate(slot)
            assertEquals(repository.snapshot.templates[slot], model.state.value.template?.text)
            model.editTemplate("edited-${slot.name}", 0, 0)
            model.confirmTemplate()
            model.confirmTemplate()
            assertEquals(slot to "edited-${slot.name}", repository.templatesWritten.last())
            assertEquals("edited-${slot.name}", model.state.value.settings.templates[slot])
        }
        assertEquals(6, repository.templatesWritten.size)
        assertEquals(arrayListOf(2, 6), tipTemplateEvents())
    }

    @Test
    fun insertionReplacesReversedSelectionAndPlacesCursorAfterPlaceholder() {
        val model = TipSettingsViewModel(FakeTipSettingsRepository(), SavedStateHandle())
        model.openTemplate(TipTemplateSlot.HeaderLeft)
        model.editTemplate("abcdef", 5, 2)
        model.insertPlaceholder(ReaderInfoTemplate.TIME)
        assertEquals("ab${ReaderInfoTemplate.TIME}f", model.state.value.template?.text)
        val cursor = 2 + ReaderInfoTemplate.TIME.length
        assertEquals(cursor, model.state.value.template?.selectionStart)
        assertEquals(cursor, model.state.value.template?.selectionEnd)
    }

    @Test
    fun outOfBoundsSelectionIsClampedAndEmptyTemplateCanBeSaved() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.openTemplate(TipTemplateSlot.FooterRight)
        model.editTemplate("abc", -50, 100)
        model.insertPlaceholder(ReaderInfoTemplate.PAGE)
        assertEquals(ReaderInfoTemplate.PAGE, model.state.value.template?.text)
        model.editTemplate("", 0, 0)
        model.confirmTemplate()
        assertEquals(TipTemplateSlot.FooterRight to "", repository.templatesWritten.single())
    }

    @Test
    fun recreationRetainsUnsavedTextSelectionAndSlotWithoutWriting() {
        val repository = FakeTipSettingsRepository()
        val handle = SavedStateHandle()
        val model = TipSettingsViewModel(repository, handle)
        model.openTemplate(TipTemplateSlot.FooterMiddle)
        model.editTemplate("unsaved draft", 7, 2)
        val restored =
            TipSettingsViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(model.state.value.template, restored.state.value.template)
        assertTrue(repository.templatesWritten.isEmpty())
        restored.insertPlaceholder(ReaderInfoTemplate.BATTERY)
        restored.confirmTemplate()
        assertEquals(
            TipTemplateSlot.FooterMiddle to "un${ReaderInfoTemplate.BATTERY} draft",
            repository.templatesWritten.single(),
        )
    }

    @Test
    fun cancelDiscardsDraftAndReopeningLoadsStoredValue() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.openTemplate(TipTemplateSlot.HeaderRight)
        model.editTemplate("cancelled", 4, 4)
        model.dismissEditors()
        model.confirmTemplate()
        assertTrue(repository.templatesWritten.isEmpty())
        model.openTemplate(TipTemplateSlot.HeaderRight)
        assertEquals("prefix-HeaderRight", model.state.value.template?.text)
    }

    @Test
    fun externalColorRefreshNeverOverwritesTemplateDraftOrSelection() {
        val repository = FakeTipSettingsRepository()
        val model = TipSettingsViewModel(repository, SavedStateHandle())
        model.openTemplate(TipTemplateSlot.HeaderMiddle)
        model.editTemplate("draft", 1, 4)
        val draft = model.state.value.template
        repository.snapshot =
            repository.snapshot.copy(
                values = repository.snapshot.values + (TipSetting.TitleColor to 0xff112233.toInt())
            )
        model.refresh()
        assertEquals(draft, model.state.value.template)
        assertEquals(0xff112233.toInt(), model.state.value.settings[TipSetting.TitleColor])
    }

    @Test
    fun eventPayloadsMatchTitleAndInfoBarContracts() {
        listOf(TipSetting.TitleMode, TipSetting.SplitTitle, TipSetting.TitleNumberSpacing).forEach {
            assertEquals(arrayListOf(5), tipSettingEvents(it))
        }
        listOf(
                TipSetting.TitleSize,
                TipSetting.TitleLineSpacing,
                TipSetting.TitleBold,
                TipSetting.TitleColor,
                TipSetting.TitleNumberSize,
                TipSetting.TitleNumberColor,
                TipSetting.TitleTop,
                TipSetting.TitleBottom,
            )
            .forEach { assertEquals(arrayListOf(8, 5), tipSettingEvents(it)) }
        listOf(
                TipSetting.HeaderMode,
                TipSetting.FooterMode,
                TipSetting.TipSize,
                TipSetting.TipColor,
                TipSetting.DividerColor,
            )
            .forEach { assertEquals(arrayListOf(2), tipSettingEvents(it)) }
        val first = tipTemplateEvents()
        first.clear()
        assertEquals(arrayListOf(2, 6), tipTemplateEvents())
    }
}
