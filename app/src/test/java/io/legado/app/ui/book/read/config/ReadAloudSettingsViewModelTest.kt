package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.PreferKey
import io.legado.app.data.preferences.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Repository : ReadAloudSettingsRepository {
        var preferences = ReadAloudPreferences(ReadAloudSwitch.entries.associateWith { false })
        val listeners = mutableListOf<(String?) -> Unit>()
        var listener: ((String?) -> Unit)? = null
        var subscriptions = 0
        var closed = 0
        var writes = 0
        var notifications = 0
        var running = false
        var getEngine: suspend () -> String = { "engine" }
        override fun load() = preferences
        override fun setSwitch(setting: ReadAloudSwitch, enabled: Boolean) {
            writes++
            preferences = preferences.copy(switches = preferences.switches + (setting to enabled))
            listener?.invoke(setting.key)
        }
        override fun setStart(mode: String) { writes++; preferences = preferences.copy(start = mode); listener?.invoke(PreferKey.readAloudStart) }
        override fun observeChanges(onChange: (String?) -> Unit): AutoCloseable {
            subscriptions++; listener = onChange; listeners += onChange
            return AutoCloseable { closed++; if (listener === onChange) listener = null }
        }
        override suspend fun engineName() = getEngine()
        override fun playbackRunning() = running
        override fun notifyPlaybackConfigurationChanged() { notifications++ }
    }
    @Test fun allSwitchesLoadAndPauseDuringCallsRequiresIgnoreFocus() = runTest(dispatcher) {
        val repository = Repository()
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        model.setSwitch(ReadAloudSwitch.PauseDuringCalls, true)
        assertEquals(0, repository.writes)
        model.setSwitch(ReadAloudSwitch.IgnoreAudioFocus, true)
        model.setSwitch(ReadAloudSwitch.PauseDuringCalls, true)
        assertTrue(model.state.value.preferences[ReadAloudSwitch.PauseDuringCalls])
        model.setSwitch(ReadAloudSwitch.IgnoreAudioFocus, false)
        assertTrue(model.state.value.preferences[ReadAloudSwitch.PauseDuringCalls])
        model.setSwitch(ReadAloudSwitch.PauseDuringCalls, false)
        assertTrue(model.state.value.preferences[ReadAloudSwitch.PauseDuringCalls])
    }
    @Test fun repeatedSwitchSelectionsWriteOnceAndAllOtherSwitchesStayIndependent() = runTest(dispatcher) {
        val repository = Repository()
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        ReadAloudSwitch.entries.filterNot { it == ReadAloudSwitch.PauseDuringCalls }.forEach {
            model.setSwitch(it, true); model.setSwitch(it, true)
        }
        assertEquals(6, repository.writes)
        assertFalse(model.state.value.preferences[ReadAloudSwitch.PauseDuringCalls])
    }
    @Test fun observerLifecycleIsIdempotentAndOldQueuedCallbacksCannotRestartPlayback() = runTest(dispatcher) {
        val repository = Repository().apply { running = true }
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        model.startObserving(); model.startObserving(); runCurrent()
        assertEquals(1, repository.subscriptions)
        val previous = repository.listeners.single()
        model.stopObserving(); model.stopObserving()
        assertEquals(1, repository.closed)
        previous(PreferKey.readAloudByPage)
        assertEquals(0, repository.notifications)
        model.startObserving(); runCurrent()
        previous(PreferKey.readAloudByPage)
        assertEquals(0, repository.notifications)
        model.stopObserving()
    }
    @Test fun onlyRunningPlaybackRelevantChangesEmitTheOriginalMediaEvent() = runTest(dispatcher) {
        val repository = Repository()
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        model.startObserving(); runCurrent()
        model.setSwitch(ReadAloudSwitch.ByPage, true)
        assertEquals(0, repository.notifications)
        repository.running = true
        model.setSwitch(ReadAloudSwitch.ByPage, false)
        model.setSwitch(ReadAloudSwitch.StreamAudio, true)
        model.setSwitch(ReadAloudSwitch.WakeLock, true)
        assertEquals(2, repository.notifications)
        repository.listener?.invoke(PreferKey.streamReadAloudAudio)
        assertEquals(3, repository.notifications)
        model.stopObserving()
    }
    @Test fun resumeRefreshesExternalValuesWithoutWritingOrPlaybackSideEffects() = runTest(dispatcher) {
        val repository = Repository().apply { running = true }
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        repository.preferences = repository.preferences.copy(start = "page",
            switches = repository.preferences.switches + (ReadAloudSwitch.IgnoreAudioFocus to true))
        model.startObserving(); runCurrent()
        assertEquals("page", model.state.value.preferences.start)
        assertTrue(model.state.value.preferences[ReadAloudSwitch.IgnoreAudioFocus])
        assertEquals(0, repository.writes)
        assertEquals(0, repository.notifications)
        assertEquals("engine", model.state.value.engineName)
        model.stopObserving()
    }
    @Test fun restoredStartPickerCancelsWithoutSavingAndValidChoiceWritesOnce() = runTest(dispatcher) {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = ReadAloudSettingsViewModel(repository, handle)
        model.openStartPicker()
        val restored = ReadAloudSettingsViewModel(repository, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertTrue(restored.state.value.showStartPicker)
        restored.dismissStartPicker()
        assertEquals(0, repository.writes)
        restored.openStartPicker()
        restored.setStart("invalid")
        assertTrue(restored.state.value.showStartPicker)
        restored.setStart("page")
        restored.setStart("page")
        assertEquals(1, repository.writes)
        assertFalse(restored.state.value.showStartPicker)
        assertEquals("page", restored.state.value.preferences.start)
    }
    @Test fun pendingNavigationRestoresAndIsConsumedOnlyByMatchingDestination() = runTest(dispatcher) {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = ReadAloudSettingsViewModel(repository, handle)
        model.navigate(ReadAloudSettingsDestination.Engine)
        model.navigate(ReadAloudSettingsDestination.Controls)
        val restored = ReadAloudSettingsViewModel(repository, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(ReadAloudSettingsDestination.Engine, restored.state.value.navigation)
        restored.navigated(ReadAloudSettingsDestination.Controls)
        assertEquals(ReadAloudSettingsDestination.Engine, restored.state.value.navigation)
        restored.navigated(ReadAloudSettingsDestination.Engine)
        assertNull(restored.state.value.navigation)
    }
    @Test fun lateEngineLookupCannotReplaceTheNewlySelectedEngineSummary() = runTest(dispatcher) {
        val repository = Repository()
        val pending = CompletableDeferred<String>()
        repository.getEngine = { withContext(NonCancellable) { pending.await() } }
        val model = ReadAloudSettingsViewModel(repository, SavedStateHandle())
        model.refreshEngine(); runCurrent()
        repository.getEngine = { "new engine" }
        model.refreshEngine(); runCurrent()
        pending.complete("old engine"); runCurrent()
        assertEquals("new engine", model.state.value.engineName)
    }
}
