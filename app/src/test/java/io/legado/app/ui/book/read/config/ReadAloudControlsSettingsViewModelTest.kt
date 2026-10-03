package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.*
import org.junit.Assert.*
import org.junit.Test

class ReadAloudControlsSettingsViewModelTest {
    private class Repository : ReadAloudControlsSettingsRepository {
        var settings =
            ReadAloudControlsSettings(
                ReadAloudControlsToggle.entries.associateWith { it.defaultValue },
                ReadAloudControlsNumber.entries.associateWith { it.defaultValue },
            )
        var listener: (() -> Unit)? = null
        val listeners = mutableListOf<() -> Unit>()
        val numberWrites = mutableListOf<Pair<ReadAloudControlsNumber, Int>>()
        var toggleWrites = 0
        var subscriptions = 0
        var closed = 0

        override fun load() = settings

        override fun setToggle(setting: ReadAloudControlsToggle, enabled: Boolean) {
            toggleWrites++
            settings = settings.copy(toggles = settings.toggles + (setting to enabled))
            listener?.invoke()
        }

        override fun setNumber(setting: ReadAloudControlsNumber, value: Int) {
            numberWrites += setting to value
            settings = settings.copy(numbers = settings.numbers + (setting to value))
            listener?.invoke()
        }

        override fun observe(onChange: () -> Unit): AutoCloseable {
            subscriptions++
            listener = onChange
            listeners += onChange
            return AutoCloseable {
                closed++
                if (listener === onChange) listener = null
            }
        }
    }

    @Test
    fun initialIndependentVisibilitySwitchesMatchLegacyDefaults() {
        val model = ReadAloudControlsSettingsViewModel(Repository(), SavedStateHandle())
        assertTrue(model.state.value.settings[ReadAloudControlsToggle.Realtime])
        assertTrue(model.state.value.settings[ReadAloudControlsToggle.Pause])
        assertTrue(model.state.value.settings[ReadAloudControlsToggle.Position])
        assertFalse(model.state.value.settings[ReadAloudControlsToggle.AutoHide])
        assertFalse(model.state.value.settings[ReadAloudControlsToggle.Drag])
        assertFalse(model.state.value.settings[ReadAloudControlsToggle.Dock])
    }

    @Test
    fun togglesPersistOnceAndOnlyRealtimeRequestsReveal() {
        val repository = Repository()
        val model = ReadAloudControlsSettingsViewModel(repository, SavedStateHandle())
        model.setToggle(ReadAloudControlsToggle.Pause, false)
        model.setToggle(ReadAloudControlsToggle.Pause, false)
        assertEquals(1, repository.toggleWrites)
        assertNull(model.state.value.action)
        assertTrue(model.state.value.settings[ReadAloudControlsToggle.Position])
        model.setToggle(ReadAloudControlsToggle.Realtime, false)
        assertEquals(ReadAloudControlsAction.Reveal, model.state.value.action)
    }

    @Test
    fun draggingUpdatesDraftAndOnlyFinishPersistsTheLatestValue() {
        val repository = Repository()
        val model = ReadAloudControlsSettingsViewModel(repository, SavedStateHandle())
        model.drag(ReadAloudControlsNumber.Width, 100)
        model.drag(ReadAloudControlsNumber.Width, 200)
        assertEquals(200, model.state.value.settings[ReadAloudControlsNumber.Width])
        assertTrue(repository.numberWrites.isEmpty())
        model.finish(ReadAloudControlsNumber.Width)
        model.finish(ReadAloudControlsNumber.Width)
        assertEquals(listOf(ReadAloudControlsNumber.Width to 200), repository.numberWrites)
    }

    @Test
    fun everyNumericControlClampsAndMicroAdjustmentsRetainTheirIncrement() {
        val repository = Repository()
        val model = ReadAloudControlsSettingsViewModel(repository, SavedStateHandle())
        model.step(ReadAloudControlsNumber.Opacity, 1)
        model.step(ReadAloudControlsNumber.Threshold, 1)
        model.step(ReadAloudControlsNumber.Width, -1)
        assertEquals(95, repository.settings[ReadAloudControlsNumber.Opacity])
        assertEquals(110, repository.settings[ReadAloudControlsNumber.Threshold])
        assertEquals(287, repository.settings[ReadAloudControlsNumber.Width])
        ReadAloudControlsNumber.entries.forEach {
            model.drag(it, 999)
            model.finish(it)
            assertEquals(it.maximum, repository.settings[it])
            model.drag(it, -999)
            model.finish(it)
            assertEquals(it.minimum, repository.settings[it])
        }
    }

    @Test
    fun processRestoreKeepsPendingDraftAndObserverRefreshNeverOverwritesIt() {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = ReadAloudControlsSettingsViewModel(repository, handle)
        model.drag(ReadAloudControlsNumber.Opacity, 37)
        val restored =
            ReadAloudControlsSettingsViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        restored.startObserving()
        repository.settings =
            repository.settings.copy(
                numbers = repository.settings.numbers + (ReadAloudControlsNumber.Width to 85)
            )
        repository.listener?.invoke()
        assertEquals(37, restored.state.value.settings[ReadAloudControlsNumber.Opacity])
        assertEquals(85, restored.state.value.settings[ReadAloudControlsNumber.Width])
        assertTrue(repository.numberWrites.isEmpty())
        restored.flush()
        restored.flush()
        assertEquals(listOf(ReadAloudControlsNumber.Opacity to 37), repository.numberWrites)
        restored.stopObserving()
    }

    @Test
    fun pendingResetTakesPriorityOverRevealAndConsumesOnlyMatchingAction() {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = ReadAloudControlsSettingsViewModel(repository, handle)
        model.requestAction(ReadAloudControlsAction.Reveal)
        model.requestAction(ReadAloudControlsAction.ResetPosition)
        model.requestAction(ReadAloudControlsAction.Reveal)
        val restored =
            ReadAloudControlsSettingsViewModel(
                repository,
                SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
            )
        assertEquals(ReadAloudControlsAction.ResetPosition, restored.state.value.action)
        restored.actionHandled(ReadAloudControlsAction.Reveal)
        assertEquals(ReadAloudControlsAction.ResetPosition, restored.state.value.action)
        restored.actionHandled(ReadAloudControlsAction.ResetPosition)
        assertNull(restored.state.value.action)
        restored.requestAction(ReadAloudControlsAction.Reveal)
        assertEquals(ReadAloudControlsAction.Reveal, restored.state.value.action)
    }

    @Test
    fun observingIsIdempotentAndQueuedOldCallbacksAreIgnoredAfterResume() {
        val repository = Repository()
        val model = ReadAloudControlsSettingsViewModel(repository, SavedStateHandle())
        model.startObserving()
        model.startObserving()
        assertEquals(1, repository.subscriptions)
        val old = repository.listeners.single()
        model.stopObserving()
        model.stopObserving()
        assertEquals(1, repository.closed)
        model.startObserving()
        repository.settings =
            repository.settings.copy(
                numbers = repository.settings.numbers + (ReadAloudControlsNumber.Width to 123)
            )
        old()
        assertEquals(288, model.state.value.settings[ReadAloudControlsNumber.Width])
        repository.listener?.invoke()
        assertEquals(123, model.state.value.settings[ReadAloudControlsNumber.Width])
        model.stopObserving()
    }
}
