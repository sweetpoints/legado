package io.legado.app.ui.book.manga.config

import androidx.lifecycle.SavedStateHandle
import com.google.gson.Gson
import io.legado.app.data.preferences.MangaFooterDraft
import io.legado.app.data.preferences.MangaFooterJson
import io.legado.app.data.preferences.MangaFooterSettingsRepository
import org.junit.Assert.*
import org.junit.Test

class MangaFooterSettingsViewModelTest {
    private class Repository(var stored: MangaFooterDraft = MangaFooterDraft()) : MangaFooterSettingsRepository {
        var reads = 0
        val events = mutableListOf<MangaFooterConfig>()
        val saves = mutableListOf<MangaFooterDraft>()
        override fun load(): MangaFooterDraft { reads++; return stored }
        override fun preview(draft: MangaFooterDraft) { events += draft.toConfig() }
        override fun save(draft: MangaFooterDraft) { saves += draft; stored = draft }
    }

    @Test fun initialLoadUsesEveryStoredFieldAndDoesNotEmitOrSave() {
        val expected = MangaFooterDraft(true, true, true, true, true, true, 1, true, true)
        val repository = Repository(expected)
        val model = MangaFooterSettingsViewModel(repository, SavedStateHandle())
        assertEquals(expected, model.state.value)
        assertEquals(1, repository.reads)
        assertTrue(repository.events.isEmpty())
        assertTrue(repository.saves.isEmpty())
    }

    @Test fun everyHideOptionChangesItsFieldAndPublishesIndependentConfigs() {
        val repository = Repository()
        val model = MangaFooterSettingsViewModel(repository, SavedStateHandle())
        MangaFooterField.entries.forEach { model.setHidden(it, true) }
        assertEquals(MangaFooterDraft(true, true, true, true, true, true, hideChapterName = true), model.state.value)
        assertEquals(7, repository.events.size)
        repository.events.forEachIndexed { index, config ->
            repository.events.drop(index + 1).forEach { assertNotSame(config, it) }
        }
        repository.events.last().hideChapter = false
        assertTrue(model.state.value.hideChapter)
        assertFalse(repository.events.first().hideChapter)
        assertTrue(repository.saves.isEmpty())
    }

    @Test fun repeatedSelectionsAndInvalidAlignmentDoNotProduceDuplicateEvents() {
        val repository = Repository()
        val model = MangaFooterSettingsViewModel(repository, SavedStateHandle())
        model.setFooterHidden(true)
        model.setFooterHidden(true)
        model.setOrientation(1)
        model.setOrientation(1)
        model.setOrientation(10)
        model.setHidden(MangaFooterField.Page, true)
        model.setHidden(MangaFooterField.Page, true)
        assertEquals(3, repository.events.size)
        assertEquals(1, model.state.value.footerOrientation)
        model.setFooterHidden(false)
        model.setOrientation(0)
        assertFalse(model.state.value.hideFooter)
        assertEquals(0, model.state.value.footerOrientation)
    }

    @Test fun recreationRestoresDraftWithoutReloadingOrSavingAndReappliesPreview() {
        val repository = Repository()
        val state = SavedStateHandle()
        val model = MangaFooterSettingsViewModel(repository, state)
        model.setHidden(MangaFooterField.ChapterName, true)
        model.setFooterHidden(true)
        model.setOrientation(1)
        val restored = MangaFooterSettingsViewModel(repository,
            SavedStateHandle(state.keys().associateWith { state.get<Any>(it) }))
        assertEquals(model.state.value, restored.state.value)
        assertEquals(1, repository.reads)
        restored.reapplyPreview()
        assertEquals(restored.state.value.toConfig(), repository.events.last())
        assertTrue(repository.saves.isEmpty())
    }

    @Test fun dismissalSavesLatestDraftOnceEvenWithDuplicateCallbacks() {
        val repository = Repository()
        val model = MangaFooterSettingsViewModel(repository, SavedStateHandle())
        model.setHidden(MangaFooterField.ProgressLabel, true)
        model.saveOnDismiss()
        model.saveOnDismiss()
        assertEquals(listOf(model.state.value), repository.saves)
        model.setHidden(MangaFooterField.ProgressLabel, false)
        model.saveOnDismiss()
        model.reapplyPreview()
        assertEquals(1, repository.saves.size)
        assertTrue(model.state.value.hideProgressRatioLabel)
    }

    @Test fun rotationDismissalDoesNotPersistButLaterUserDismissalDoes() {
        val repository = Repository()
        val model = MangaFooterSettingsViewModel(repository, SavedStateHandle())
        model.setFooterHidden(true)
        model.saveOnDismiss(isChangingConfigurations = true)
        assertTrue(repository.saves.isEmpty())
        assertFalse(repository.stored.hideFooter)
        model.saveOnDismiss(isChangingConfigurations = false)
        assertTrue(repository.stored.hideFooter)
        assertEquals(1, repository.saves.size)
    }

    @Test fun legacyJsonAndUnknownFieldsPreserveBackupCompatibility() {
        val json = """{"hideChapter":true,"hideChapterName":true,"footerOrientation":1,"futureField":true}"""
        assertEquals(MangaFooterDraft(hideChapter = true, footerOrientation = 1, hideChapterName = true), MangaFooterJson.decode(json))
        val draft = MangaFooterDraft(true, true, true, true, true, true, 1, true, true)
        val config = Gson().fromJson(MangaFooterJson.encode(draft), MangaFooterConfig::class.java)
        assertEquals(draft.toConfig(), config)
        assertEquals(draft, MangaFooterJson.decode(MangaFooterJson.encode(draft)))
    }

    @Test fun malformedOrMissingJsonUsesSafeDefaults() {
        listOf(null, "", "{", "null", "[]").forEach {
            assertEquals(MangaFooterDraft(), MangaFooterJson.decode(it))
        }
    }
}
