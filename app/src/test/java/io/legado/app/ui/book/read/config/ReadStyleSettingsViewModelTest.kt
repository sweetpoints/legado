package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.junit.Assert.*
import org.junit.Test

class ReadStyleSettingsViewModelTest {
    private class Repository : ReadStyleSettingsRepository {
        var value = ReadStyleSettingsSnapshot((0..4).map { ReadStylePreset(it, "Style $it", 0, "{}") },
            0, false, 15, 50, 22, 10, "", 0, 0, 2, 3)
        val dispatched = mutableListOf<ReadStyleUpdate>(); var saves = 0; var restores = 0; var animationEdits = 0
        override fun load() = value
        override fun checkpoint() = GSON.toJson(value)
        override fun restore(checkpoint: String) { restores++; value = GSON.fromJsonObject<ReadStyleSettingsSnapshot>(checkpoint).getOrThrow() }
        override fun slider(slider: ReadStyleSlider, progress: Int): ReadStyleUpdate {
            val p = progress.coerceIn(0, slider.maximum)
            value = when (slider) { ReadStyleSlider.TextSize -> value.copy(textSize = p); ReadStyleSlider.LetterSpacing -> value.copy(letterSpacing = p)
                ReadStyleSlider.LineSpacing -> value.copy(lineSpacing = p); ReadStyleSlider.ParagraphSpacing -> value.copy(paragraphSpacing = p) }
            return ReadStyleUpdate(codes = listOf(8, 5))
        }
        override fun select(index: Int): ReadStyleUpdate {
            if (index == value.selected || index !in value.presets.indices) return ReadStyleUpdate()
            val before = value.pageAnimation; value = value.copy(selected = index, pageAnimation = if (index == 2) 4 else 3)
            return ReadStyleUpdate(animationChanged = before != value.pageAnimation, codes = listOf(1, 2, 5), updateActionBar = true)
        }
        override fun shared(value: Boolean): ReadStyleUpdate {
            if (value == this.value.shared) return ReadStyleUpdate()
            this.value = this.value.copy(shared = value); return ReadStyleUpdate(codes = listOf(1, 2, 5))
        }
        override fun animation(value: Int): ReadStyleUpdate { animationEdits++; this.value = this.value.copy(pageAnimation = value); return ReadStyleUpdate(true, true) }
        override fun weight(value: Int): ReadStyleUpdate { this.value = this.value.copy(weight = value); return ReadStyleUpdate(codes = listOf(8, 9, 6)) }
        override fun chinese(value: Int): ReadStyleUpdate { this.value = this.value.copy(chinese = value); return ReadStyleUpdate(codes = listOf(5)) }
        override fun indent(value: Int): ReadStyleUpdate { this.value = this.value.copy(indent = value); return ReadStyleUpdate(codes = listOf(8, 5)) }
        override fun font(path: String): ReadStyleUpdate {
            if (path == value.font && path.isNotEmpty()) return ReadStyleUpdate()
            value = value.copy(font = path); return ReadStyleUpdate(codes = listOf(2, 5))
        }
        override fun addPreset(): Int { val index = value.presets.size; value = value.copy(presets = value.presets + ReadStylePreset(index, "New", 0, "{}")); return index }
        override fun dispatch(update: ReadStyleUpdate) { dispatched += update }
        override fun save() { saves++ }
    }
    private fun restored(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun loadingRefreshingPresetsAndSharedLayoutNeverReplayAnimationSelection() {
        val repo = Repository(); val model = ReadStyleSettingsViewModel(repo, SavedStateHandle())
        model.refresh(); model.preset(1); model.shared(true); model.refresh()
        assertEquals(0, repo.animationEdits); assertFalse(model.state.value.pending.any { it.update?.let { update -> update.animationChanged || update.reloadContent } == true })
        assertEquals(listOf(listOf(1, 2, 5), listOf(1, 2, 5)), model.state.value.pending.map { it.update!!.codes })
        model.preset(2); assertTrue(model.state.value.pending.last().update!!.animationChanged)
        assertFalse(model.state.value.pending.last().update!!.reloadContent)
    }
    @Test fun animationSelectionHasExactlyOnePendingCallbackAndReloadEvenAcrossRestore() {
        val saved = SavedStateHandle(); val repo = Repository(); val model = ReadStyleSettingsViewModel(repo, saved)
        model.animation(0); model.animation(0); assertEquals(1, repo.animationEdits)
        val recoveredRepo = Repository(); val recoveredSaved = restored(saved); val recovered = ReadStyleSettingsViewModel(recoveredRepo, recoveredSaved)
        assertEquals(0, recovered.state.value.settings.pageAnimation); assertEquals(0, recoveredRepo.animationEdits)
        val effect = recovered.state.value.pending.single(); assertTrue(effect.update!!.animationChanged); assertTrue(effect.update.reloadContent)
        recovered.completed(effect.id); recovered.completed(effect.id)
        assertEquals(listOf(effect.update), recoveredRepo.dispatched); assertTrue(recovered.state.value.pending.isEmpty())
        assertTrue(ReadStyleSettingsViewModel(Repository(), restored(recoveredSaved)).state.value.pending.isEmpty())
    }
    @Test fun slidersClampEndpointsAndDisplayOriginalProgressWithoutDuplicateOperations() {
        val repo = Repository(); val model = ReadStyleSettingsViewModel(repo, SavedStateHandle())
        for (slider in ReadStyleSlider.entries) {
            model.slider(slider, -1); assertEquals(0, model.state.value.settings.progress(slider))
            model.slider(slider, slider.maximum + 100); assertEquals(slider.maximum, model.state.value.settings.progress(slider))
            val count = model.state.value.pending.size; model.slider(slider, slider.maximum); assertEquals(count, model.state.value.pending.size)
        }
        assertEquals("5", ReadStyleSlider.TextSize.display(0)); assertEquals("50", ReadStyleSlider.TextSize.display(45))
        assertEquals("-0.5", ReadStyleSlider.LetterSpacing.display(0)); assertEquals("0.5", ReadStyleSlider.LetterSpacing.display(100))
        assertEquals("-2.0", ReadStyleSlider.LineSpacing.display(0)); assertEquals("3.0", ReadStyleSlider.LineSpacing.display(50))
        assertEquals("2.0", ReadStyleSlider.ParagraphSpacing.display(20)); assertTrue(model.state.value.pending.all { it.update!!.codes == listOf(8, 5) })
    }
    @Test fun optionPickerRestoresAndCancellationWritesNothingWhileSelectionsHaveExactEvents() {
        val saved = SavedStateHandle(); val repo = Repository(); val model = ReadStyleSettingsViewModel(repo, saved)
        model.picker(ReadStylePicker.Chinese); model.picker(null); assertTrue(model.state.value.pending.isEmpty())
        model.picker(ReadStylePicker.Weight); val restored = ReadStyleSettingsViewModel(repo, restored(saved)); assertEquals(ReadStylePicker.Weight, restored.state.value.picker)
        restored.pick(2); restored.picker(ReadStylePicker.Chinese); restored.pick(1); restored.picker(ReadStylePicker.Indent); restored.pick(0)
        assertEquals(listOf(listOf(8, 9, 6), listOf(5), listOf(8, 5)), restored.state.value.pending.map { it.update!!.codes })
        assertNull(restored.state.value.picker); assertEquals(0, restored.state.value.settings.indent)
    }
    @Test fun fontDefaultRefreshesEveryTimeAndNonemptyRepeatedPathDoesNot() {
        val model = ReadStyleSettingsViewModel(Repository(), SavedStateHandle())
        model.font(""); model.font(""); model.font("font.ttf"); model.font("font.ttf")
        assertEquals(3, model.state.value.pending.size); assertTrue(model.state.value.pending.all { it.update!!.codes == listOf(2, 5) })
    }
    @Test fun addAndEditSelectPresetBeforeBackgroundDestinationAndEffectsAreMonotonic() {
        val model = ReadStyleSettingsViewModel(Repository(), SavedStateHandle())
        model.addPreset(); val added = model.state.value.pending.single()
        assertEquals(5, model.state.value.settings.selected); assertEquals(ReadStyleDestination.Background, added.destination)
        model.completed(added.id); model.open(ReadStyleDestination.Font); assertTrue(model.state.value.pending.single().id > added.id)
        model.editPreset(-1); assertEquals(1, model.state.value.pending.size)
    }
    @Test fun configurationTeardownDoesNotSaveAndDuplicateCloseSavesOnlyOnceIncludingRestore() {
        val repo = Repository(); val saved = SavedStateHandle(); val model = ReadStyleSettingsViewModel(repo, saved)
        model.slider(ReadStyleSlider.TextSize, 18); model.dismissed(true); assertEquals(0, repo.saves); assertFalse(model.state.value.finished)
        model.dismissed(false); model.dismissed(false); assertEquals(1, repo.saves)
        val restored = ReadStyleSettingsViewModel(repo, restored(saved)); restored.dismissed(false); restored.slider(ReadStyleSlider.TextSize, 25)
        assertEquals(1, repo.saves); assertEquals(18, restored.state.value.settings.textSize)
    }
    @Test fun restoringFinishedDialogDoesNotOverwriteNewerGlobalSettings() {
        val repo = Repository(); val saved = SavedStateHandle(); val model = ReadStyleSettingsViewModel(repo, saved)
        model.slider(ReadStyleSlider.TextSize, 18); model.dismissed(false)
        repo.value = repo.value.copy(textSize = 30)
        val restored = ReadStyleSettingsViewModel(repo, restored(saved))
        assertTrue(restored.state.value.finished); assertEquals(30, restored.state.value.settings.textSize)
        assertEquals(0, repo.restores)
    }

}
