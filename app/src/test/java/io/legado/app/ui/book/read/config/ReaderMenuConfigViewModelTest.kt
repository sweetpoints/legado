package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.ReaderMenuSettingsRepository
import io.legado.app.help.ReaderMenuConfig
import org.junit.Assert.*
import org.junit.Test

class ReaderMenuConfigViewModelTest {
    private val keys = ReaderMenuConfig.ALL_KEYS

    private fun base() = ReaderMenuConfig(keys.take(3), keys.drop(3))

    @Test
    fun toggleMovesEntryToEndOfDestinationWithoutHidingActions() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.Toggle(keys[1], false))
        assertEquals(listOf(keys[0], keys[2]), repo.saved.last().primary)
        assertEquals(keys.drop(3) + keys[1], repo.saved.last().more)
        model.edit(ReaderMenuEditAction.Toggle(keys[4], true))
        assertEquals(listOf(keys[0], keys[2], keys[4]), repo.saved.last().primary)
        assertEquals(keys.toSet(), model.state.value.entries.map { it.key }.toSet())
    }

    @Test
    fun selectingAllAndNoneKeepCurrentOrderAndResetRestoresDefault() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.Toggle(keys[0], false))
        val order = model.state.value.entries.map { it.key }
        model.edit(ReaderMenuEditAction.SetAll(false))
        assertEquals(order, repo.saved.last().more)
        assertTrue(repo.saved.last().primary.isEmpty())
        model.edit(ReaderMenuEditAction.SetAll(true))
        assertEquals(order, repo.saved.last().primary)
        assertTrue(repo.saved.last().more.isEmpty())
        model.edit(ReaderMenuEditAction.Reset)
        assertEquals(ReaderMenuConfig.default(), repo.saved.last())
    }

    @Test
    fun reorderCannotCrossGroupAndOnlyGestureEndPersists() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.StartReorder(keys[0]))
        model.edit(ReaderMenuEditAction.Move(keys[0], keys[3]))
        assertEquals(keys, model.state.value.entries.map { it.key })
        model.edit(ReaderMenuEditAction.Move(keys[0], keys[2]))
        assertEquals(
            listOf(keys[2], keys[1], keys[0]),
            model.state.value.entries.take(3).map { it.key },
        )
        assertTrue(repo.saved.isEmpty())
        model.edit(ReaderMenuEditAction.FinishGesture(true))
        assertEquals(listOf(keys[2], keys[1], keys[0]), repo.saved.single().primary)
        assertEquals(keys.drop(3), repo.saved.single().more)
    }

    @Test
    fun cancelledReorderRestoresSnapshotWithoutSavingOrRefresh() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.StartReorder(keys[0]))
        model.edit(ReaderMenuEditAction.Move(keys[0], keys[2]))
        model.edit(ReaderMenuEditAction.FinishGesture(false))
        assertEquals(keys, model.state.value.entries.map { it.key })
        assertTrue(repo.saved.isEmpty())
        assertEquals(0, model.state.value.refreshRequest)
    }

    @Test
    fun slideSelectionExtendsReversesAndGroupsOnceAtEnd() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.StartSelection(keys[0]))
        model.edit(ReaderMenuEditAction.SelectionTo(keys[4]))
        assertTrue(model.state.value.entries.take(5).none { it.primary })
        model.edit(ReaderMenuEditAction.SelectionTo(keys[1]))
        assertFalse(model.state.value.entries[0].primary)
        assertFalse(model.state.value.entries[1].primary)
        assertTrue(model.state.value.entries.subList(2, 5).all { it.primary })
        assertTrue(repo.saved.isEmpty())
        model.edit(ReaderMenuEditAction.FinishGesture(true))
        assertEquals(keys.subList(2, 5), repo.saved.single().primary)
        assertEquals(keys.take(2) + keys.drop(5), repo.saved.single().more)
    }

    @Test
    fun slideStartingInMoreSelectsRangeAndCancellationRestoresGrouping() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.StartSelection(keys[4]))
        model.edit(ReaderMenuEditAction.SelectionTo(keys[6]))
        assertTrue(model.state.value.entries.subList(4, 7).all { it.primary })
        model.edit(ReaderMenuEditAction.FinishGesture(false))
        assertEquals(base().primary, model.state.value.entries.filter { it.primary }.map { it.key })
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun accessibilityStepOnlySwapsWithinGroupAndPersistsImmediately() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.Step(keys[2], 1))
        assertTrue(repo.saved.isEmpty())
        model.edit(ReaderMenuEditAction.Step(keys[1], -1))
        assertEquals(listOf(keys[1], keys[0], keys[2]), repo.saved.single().primary)
    }

    @Test
    fun restoredDraftKeepsExactGroupOrdersWithoutReloadOrUnintendedSave() {
        val repo = FakeRepository(base())
        val handle = SavedStateHandle()
        val model = ReaderMenuConfigViewModel(repo, handle)
        model.edit(ReaderMenuEditAction.Toggle(keys[0], false))
        model.edit(ReaderMenuEditAction.Step(keys[2], -1))
        val restored =
            ReaderMenuConfigViewModel(
                repo,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(model.state.value.entries, restored.state.value.entries)
        assertEquals(1, repo.loads)
        assertEquals(2, repo.saved.size)
        assertEquals(model.state.value.refreshRequest, restored.state.value.refreshRequest)
    }

    @Test
    fun saveFailureRetainsDraftAndRetryUsesSamePayloadBeforeRequestingRefresh() {
        val repo = FakeRepository(base()).apply { failSave = true }
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.Toggle(keys[0], false))
        val draft = model.state.value.entries
        assertEquals("save failed", model.state.value.error)
        assertEquals(0, model.state.value.refreshRequest)
        repo.failSave = false
        model.edit(ReaderMenuEditAction.RetrySave)
        assertNull(model.state.value.error)
        assertEquals(draft, model.state.value.entries)
        assertEquals(keys[0], repo.saved.single().more.last())
        assertEquals(1, model.state.value.refreshRequest)
    }

    @Test
    fun invalidActionsAndRepeatedGestureEndDoNotWritePreferences() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.Toggle("missing", false))
        model.edit(ReaderMenuEditAction.Toggle(keys[0], true))
        model.edit(ReaderMenuEditAction.StartSelection("missing"))
        model.edit(ReaderMenuEditAction.Move(keys[0], keys[1]))
        model.edit(ReaderMenuEditAction.FinishGesture(true))
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun processRestorationDuringReorderUsesPreGestureSnapshot() {
        val repo = FakeRepository(base())
        val handle = SavedStateHandle()
        val model = ReaderMenuConfigViewModel(repo, handle)
        model.edit(ReaderMenuEditAction.StartReorder(keys[0]))
        model.edit(ReaderMenuEditAction.Move(keys[0], keys[2]))
        assertNotEquals(keys, model.state.value.entries.map { it.key })
        val restored =
            ReaderMenuConfigViewModel(
                repo,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(keys, restored.state.value.entries.map { it.key })
        assertEquals(
            base().primary,
            restored.state.value.entries.filter { it.primary }.map { it.key },
        )
        assertEquals(0, restored.state.value.refreshRequest)
        assertTrue(repo.saved.isEmpty())
        assertEquals(1, repo.loads)
    }

    @Test
    fun processRestorationDuringSlideDropsUnfinishedSelection() {
        val repo = FakeRepository(base())
        val handle = SavedStateHandle()
        val model = ReaderMenuConfigViewModel(repo, handle)
        model.edit(ReaderMenuEditAction.StartSelection(keys[0]))
        model.edit(ReaderMenuEditAction.SelectionTo(keys[4]))
        assertFalse(model.state.value.entries[0].primary)
        val restored =
            ReaderMenuConfigViewModel(
                repo,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(
            base().primary,
            restored.state.value.entries.filter { it.primary }.map { it.key },
        )
        assertEquals(
            base().more,
            restored.state.value.entries.filterNot { it.primary }.map { it.key },
        )
        assertEquals(0, restored.state.value.refreshRequest)
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun staleRefreshAcknowledgementDoesNotConsumeNewSave() {
        val repo = FakeRepository(base())
        val model = ReaderMenuConfigViewModel(repo, SavedStateHandle())
        model.edit(ReaderMenuEditAction.SetAll(false))
        model.edit(ReaderMenuEditAction.SetAll(true))
        model.refreshed(1)
        assertEquals(2, model.state.value.refreshRequest)
        model.refreshed(2)
        assertEquals(0, model.state.value.refreshRequest)
    }

    private class FakeRepository(val config: ReaderMenuConfig) : ReaderMenuSettingsRepository {
        var loads = 0
        var failSave = false
        val saved = mutableListOf<ReaderMenuConfig>()

        override fun load(): ReaderMenuConfig {
            loads++
            return config
        }

        override fun save(config: ReaderMenuConfig) {
            if (failSave) error("save failed")
            saved += config
        }
    }
}
