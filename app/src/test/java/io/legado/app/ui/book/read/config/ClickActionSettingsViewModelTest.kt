package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.ClickActionRegion
import io.legado.app.data.preferences.ClickActionSettingsRepository
import org.junit.Assert.*
import org.junit.Test

class ClickActionSettingsViewModelTest {
    private class Repository : ClickActionSettingsRepository {
        var actions = ClickActionRegion.entries.associateWith { it.defaultAction }
        val writes = mutableListOf<Pair<ClickActionRegion, Int>>()
        var guards = 0
        val listeners = mutableListOf<() -> Unit>()
        var removals = 0
        override fun load() = actions
        override fun setAction(region: ClickActionRegion, action: Int) { actions = actions + (region to action); writes += region to action }
        override fun ensureMenuAction() { guards++; if (actions.values.none { it == 0 }) setAction(ClickActionRegion.MiddleCenter, 0) }
        override fun observe(onChange: () -> Unit): AutoCloseable { listeners += onChange; return AutoCloseable { removals++ } }
    }
    @Test fun defaultsMatchNineOriginalPreferenceRegions() {
        val model = ClickActionSettingsViewModel(Repository(), SavedStateHandle())
        assertEquals(listOf(2, 2, 1, 2, 0, 1, 2, 1, 1), ClickActionRegion.entries.map { model.state.value.actions[it] })
    }
    @Test fun allFifteenActionsWriteOnlyTheChosenRegionImmediately() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        for (action in -1..13) {
            model.selectRegion(ClickActionRegion.TopLeft)
            model.selectAction(action)
            assertEquals(action, repo.actions[ClickActionRegion.TopLeft])
            assertNull(model.state.value.editing)
        }
        assertEquals(15, repo.writes.size)
        assertTrue(repo.writes.all { it.first == ClickActionRegion.TopLeft })
        assertEquals(0, repo.actions[ClickActionRegion.MiddleCenter])
    }
    @Test fun choosingSameActionCancellingOrInvalidActionNeverWrites() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        model.selectAction(8)
        model.selectRegion(ClickActionRegion.TopLeft); model.selectAction(2)
        model.selectRegion(ClickActionRegion.TopLeft); model.selectAction(99); model.dismissPicker()
        assertTrue(repo.writes.isEmpty())
        assertNull(model.state.value.editing)
    }
    @Test fun processRestoresPickerButLoadsLatestPersistedActions() {
        val repo = Repository(); val state = SavedStateHandle(); val model = ClickActionSettingsViewModel(repo, state)
        model.selectRegion(ClickActionRegion.BottomRight)
        repo.setAction(ClickActionRegion.BottomRight, 13)
        val restored = ClickActionSettingsViewModel(repo, SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
        assertEquals(ClickActionRegion.BottomRight, restored.state.value.editing)
        assertEquals(13, restored.state.value.actions[ClickActionRegion.BottomRight])
        assertEquals(1, repo.writes.size)
    }
    @Test fun closeRestoresMenuOnlyWhenMissingAndIsIdempotent() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        model.selectRegion(ClickActionRegion.MiddleCenter); model.selectAction(-1)
        model.close(); model.close()
        assertEquals(1, repo.guards)
        assertEquals(0, repo.actions[ClickActionRegion.MiddleCenter])
        assertEquals(2, repo.writes.size)
        model.selectRegion(ClickActionRegion.TopLeft); model.selectAction(9)
        assertEquals(2, repo.writes.size)
    }
    @Test fun menuInAnotherRegionPreservesCustomCenterAtClose() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        model.selectRegion(ClickActionRegion.TopLeft); model.selectAction(0)
        model.selectRegion(ClickActionRegion.MiddleCenter); model.selectAction(13)
        model.close()
        assertEquals(13, repo.actions[ClickActionRegion.MiddleCenter])
        assertEquals(2, repo.writes.size)
    }
    @Test fun lifecycleObservationRefreshesAndRejectsQueuedOldCallbacks() {
        val repo = Repository(); val model = ClickActionSettingsViewModel(repo, SavedStateHandle())
        model.startObserving(); model.startObserving()
        assertEquals(1, repo.listeners.size)
        repo.actions = repo.actions + (ClickActionRegion.TopLeft to 7); repo.listeners[0]()
        assertEquals(7, model.state.value.actions[ClickActionRegion.TopLeft])
        model.stopObserving(); model.stopObserving()
        repo.actions = repo.actions + (ClickActionRegion.TopLeft to 9); repo.listeners[0]()
        assertEquals(7, model.state.value.actions[ClickActionRegion.TopLeft])
        model.startObserving()
        assertEquals(9, model.state.value.actions[ClickActionRegion.TopLeft])
        assertEquals(1, repo.removals)
    }
}
