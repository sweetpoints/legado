package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.TextSelectMenuSettingsRepository
import io.legado.app.help.TextSelectMenuConfig
import org.junit.Assert.*
import org.junit.Test

class TextSelectMenuSettingsViewModelTest {
    private class Repository : TextSelectMenuSettingsRepository {
        var config = TextSelectMenuConfig.default()
        var writes = 0
        var failure = false
        override fun load() = config
        override fun save(config: TextSelectMenuConfig) {
            if (failure) error("write failed")
            this.config = config; writes++
        }
    }
    private fun model(repository: Repository = Repository(), state: SavedStateHandle = SavedStateHandle()) =
        TextSelectMenuSettingsViewModel(repository, state)
    @Test fun transferPreservesOldFirstMoreAndLastBarInsertion() {
        val repo = Repository(); val model = model(repo)
        model.edit(TextSelectMenuSettingsAction.Transfer("copy"))
        assertEquals(listOf("copy") + TextSelectMenuConfig.DEFAULT_MORE, repo.config.more)
        assertFalse("copy" in repo.config.bar)
        model.edit(TextSelectMenuSettingsAction.Transfer("copy"))
        assertEquals(TextSelectMenuConfig.DEFAULT_BAR.filterNot { it == "copy" } + "copy", repo.config.bar)
        assertEquals(2, repo.writes)
        assertEquals(TextSelectMenuConfig.ALL_KEYS.toSet(), (repo.config.bar + repo.config.more).toSet())
    }
    @Test fun draggingAcrossDividerWritesOnlyOnCompletionAndOnlyOnce() {
        val repo = Repository(); val model = model(repo)
        model.edit(TextSelectMenuSettingsAction.StartDrag("aloud"))
        model.edit(TextSelectMenuSettingsAction.MoveTo(TextSelectMenuSettingsViewModel.ZONE_MORE))
        assertEquals(0, repo.writes)
        assertTrue(model.state.value.rows.indexOf("aloud") > model.state.value.rows.indexOf(TextSelectMenuSettingsViewModel.ZONE_MORE))
        model.edit(TextSelectMenuSettingsAction.FinishDrag(true))
        model.edit(TextSelectMenuSettingsAction.FinishDrag(true))
        assertEquals("aloud", repo.config.more.first())
        assertEquals(1, repo.writes)
    }
    @Test fun cancelledAndInterruptedDragRestoreBaselineWithoutSavingTemporaryOrder() {
        val repo = Repository(); val state = SavedStateHandle(); val model = model(repo, state)
        val before = model.state.value.rows
        model.edit(TextSelectMenuSettingsAction.StartDrag("copy"))
        model.edit(TextSelectMenuSettingsAction.MoveTo("browser"))
        val restored = model(repo, SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        assertEquals(before, restored.state.value.rows)
        model.cancelGesture()
        assertEquals(before, model.state.value.rows)
        assertEquals(0, repo.writes)
    }
    @Test fun finishedEditsRestoreEvenWhenRepositoryChangesAfterProcessDeath() {
        val repo = Repository(); val state = SavedStateHandle(); val model = model(repo, state)
        model.edit(TextSelectMenuSettingsAction.Transfer("dict"))
        val expected = model.state.value.rows
        repo.config = TextSelectMenuConfig.default()
        assertEquals(expected, model(repo, SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })).state.value.rows)
    }
    @Test fun accessibilityStepsReorderAndCrossBothWaysWithoutLosingKeys() {
        val repo = Repository(); val model = model(repo)
        model.edit(TextSelectMenuSettingsAction.Step("copy", -1))
        assertEquals("copy", repo.config.bar.first())
        model.edit(TextSelectMenuSettingsAction.Step("dict", -1))
        assertEquals("dict", repo.config.bar.last())
        model.edit(TextSelectMenuSettingsAction.Step("dict", 1))
        assertEquals("dict", repo.config.more.first())
        assertEquals(3, repo.writes)
        assertEquals(10, repo.config.bar.size + repo.config.more.size)
    }
    @Test fun resetCancelsActiveGestureAndRestoresDefaultsWithoutExtraWrites() {
        val repo = Repository(); val model = model(repo)
        model.edit(TextSelectMenuSettingsAction.Transfer("copy"))
        model.edit(TextSelectMenuSettingsAction.StartDrag("dict"))
        model.edit(TextSelectMenuSettingsAction.MoveTo("replace"))
        model.edit(TextSelectMenuSettingsAction.Transfer("copy"))
        model.edit(TextSelectMenuSettingsAction.Reset)
        model.edit(TextSelectMenuSettingsAction.FinishDrag(true))
        model.edit(TextSelectMenuSettingsAction.Reset)
        assertEquals(TextSelectMenuConfig.default(), repo.config)
        assertEquals(2, repo.writes)
    }
    @Test fun failedWriteKeepsVisibleAndSavedDraftAndRetryPersistsIt() {
        val repo = Repository(); val state = SavedStateHandle(); val model = model(repo, state)
        repo.failure = true
        model.edit(TextSelectMenuSettingsAction.Transfer("copy"))
        assertEquals("write failed", model.state.value.error)
        assertTrue(TextSelectMenuConfig.fromJson(state["textSelectMenu.config"]).more.first() == "copy")
        repo.failure = false
        model.edit(TextSelectMenuSettingsAction.Retry)
        assertNull(model.state.value.error)
        assertEquals("copy", repo.config.more.first())
    }
    @Test fun noOpGesturesInvalidKeysAndBoundsDoNotWrite() {
        val repo = Repository(); val model = model(repo)
        model.edit(TextSelectMenuSettingsAction.Transfer("unknown"))
        model.edit(TextSelectMenuSettingsAction.Step("replace", -1))
        model.edit(TextSelectMenuSettingsAction.StartDrag("unknown"))
        model.edit(TextSelectMenuSettingsAction.FinishDrag(true))
        model.edit(TextSelectMenuSettingsAction.StartDrag("copy"))
        model.edit(TextSelectMenuSettingsAction.MoveTo("replace"))
        model.edit(TextSelectMenuSettingsAction.MoveTo("replace"))
        model.edit(TextSelectMenuSettingsAction.FinishDrag(true))
        assertEquals(0, repo.writes)
    }
}
