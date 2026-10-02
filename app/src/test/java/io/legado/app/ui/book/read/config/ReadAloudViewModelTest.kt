package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.ReadAloudControlPreferences
import io.legado.app.data.preferences.ReadAloudControlRepository
import io.legado.app.data.preferences.ReadAloudControlRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadAloudViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun initialTimerUsesMinuteThenChapterThenSavedDefaultAndBoundsRate() = runTest(dispatcher) {
        val repo = FakeRepository().apply { prefs = prefs.copy(rate = 999, defaultTimer = 20); playback = playback.copy(minute = 10) }
        val first = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        assertEquals(10, first.state.value.timer)
        assertEquals(45, first.state.value.rate)
        repo.playback = repo.playback.copy(minute = 0, chapter = 3)
        val chapters = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        assertEquals(0, chapters.state.value.timer)
        repo.playback = repo.playback.copy(chapter = 0)
        val inactive = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        assertEquals(20, inactive.state.value.timer)
    }

    @Test fun rateDragOnlyPersistsAtCompletionAndEachEffectIsConsumedOnce() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.changeRate(12)
        assertTrue(repo.savedRates.isEmpty())
        assertTrue(model.state.value.pending.isEmpty())
        assertEquals("1.7", model.state.value.rateText)
        model.finishRate()
        assertEquals(listOf(12), repo.savedRates)
        val effect = model.state.value.pending.single()
        assertEquals(ReadAloudControl.UpdateRate, effect.control)
        assertEquals(effect, model.consumeEffect(effect.id))
        assertNull(model.consumeEffect(effect.id))
    }

    @Test fun stepsClampAtBothEndsAndFollowSystemDisablesRateChanges() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.changeRate(999); model.finishRate(); model.stepRate(1)
        assertEquals(listOf(45), repo.savedRates)
        model.changeRate(-1); model.finishRate(); model.stepRate(-1)
        assertEquals(listOf(45, 0), repo.savedRates)
        model.setFollowSystem(true)
        model.changeRate(20); model.stepRate(1); model.finishRate()
        assertEquals(0, model.state.value.rate)
        assertTrue(repo.prefs.followSystem)
        assertEquals(listOf(45, 0), repo.savedRates)
        assertEquals(3, model.state.value.pending.size)
    }

    @Test fun timerDragSurvivesServiceUpdatesAndCommitsExactClampedMinute() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.changeTimer(200)
        repo.playback = repo.playback.copy(minute = 10)
        model.refreshRuntime(timerEvent = 10)
        assertEquals(180, model.state.value.timer)
        assertTrue(model.state.value.timerEditing)
        assertTrue(model.state.value.pending.isEmpty())
        model.finishTimer()
        assertFalse(model.state.value.timerEditing)
        assertEquals(ReadAloudEffect(1, ReadAloudControl.SetTimer, 180), model.state.value.pending.single())
        assertEquals(180, model.state.value.minute)
    }

    @Test fun timerZeroAndChapterEventsKeepDisplayedSliderAndModeConsistent() = runTest(dispatcher) {
        val repo = FakeRepository().apply { prefs = prefs.copy(defaultTimer = 20) }
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.refreshRuntime(timerEvent = 0)
        assertEquals(0, model.state.value.timer)
        model.refreshRuntime(chapterEvent = 3)
        assertEquals(3, model.state.value.chapter)
        assertEquals(0, model.state.value.timer)
        repo.playback = repo.playback.copy(minute = 8, chapter = 0)
        model.refreshRuntime(timerEvent = 8)
        assertEquals(8, model.state.value.minute)
        assertEquals(8, model.state.value.timer)
    }

    @Test fun childSleepSelectionsEnqueueDifferentServicesAndDefaultSaveOnlyWritesPreference() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.setSleepChapter(3)
        assertEquals(3, model.state.value.chapter)
        assertEquals(ReadAloudEffect(1, ReadAloudControl.SetChapterStop, 3), model.state.value.pending.single())
        model.setSleepMinute(25)
        assertEquals(0, model.state.value.chapter)
        assertEquals(25, model.state.value.minute)
        model.saveDefaultTimer()
        assertEquals(listOf(25), repo.savedTimers)
        assertEquals(listOf(ReadAloudControl.SetChapterStop, ReadAloudControl.SetTimer, ReadAloudControl.TimerSaved),
            model.state.value.pending.map { it.control })
    }

    @Test fun pendingEffectsAndUncommittedDraftRestoreWithoutReapplyingPreferences() = runTest(dispatcher) {
        val repo = FakeRepository()
        val handle = SavedStateHandle()
        val model = ReadAloudViewModel(repo, handle)
        runCurrent()
        model.changeRate(20)
        model.changeTimer(30)
        model.request(ReadAloudControl.Engine)
        val restored = ReadAloudViewModel(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        runCurrent()
        assertEquals(20, restored.state.value.rate)
        assertEquals(30, restored.state.value.timer)
        assertTrue(restored.state.value.timerEditing)
        assertEquals(model.state.value.pending, restored.state.value.pending)
        assertTrue(repo.savedRates.isEmpty())
        assertTrue(repo.savedTimers.isEmpty())
    }

    @Test fun closeEffectsAreQueuedOnceAndRestoredFinishedNeverRepeatsServiceCommand() = runTest(dispatcher) {
        val repo = FakeRepository()
        val handle = SavedStateHandle()
        val model = ReadAloudViewModel(repo, handle)
        runCurrent()
        model.request(ReadAloudControl.Stop)
        model.request(ReadAloudControl.Stop)
        model.request(ReadAloudControl.MainMenu)
        val effect = model.state.value.pending.single()
        assertNotNull(model.consumeEffect(effect.id))
        assertTrue(model.state.value.finished)
        val restored = ReadAloudViewModel(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertTrue(restored.state.value.finished)
        assertTrue(restored.state.value.pending.isEmpty())
        restored.request(ReadAloudControl.Stop)
        assertTrue(restored.state.value.pending.isEmpty())
    }

    @Test fun engineLateResultCannotOverwriteNewSelectionOrUpdateClosedDialog() = runTest(dispatcher) {
        val pending = CompletableDeferred<String>()
        val repo = FakeRepository().apply { engine = { withContext(NonCancellable) { pending.await() } } }
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        repo.engine = { "New engine" }
        model.reloadEngine()
        runCurrent()
        pending.complete("Old engine")
        runCurrent()
        assertEquals("New engine", model.state.value.engineName)
        model.request(ReadAloudControl.Stop)
        model.consumeEffect(model.state.value.pending.single().id)
        model.reloadEngine()
        runCurrent()
        assertEquals("New engine", model.state.value.engineName)
    }

    @Test fun failedPreferenceSaveRetainsDraftAndDoesNotRequestServiceRateUpdate() = runTest(dispatcher) {
        val repo = FakeRepository().apply { failSave = true }
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.changeRate(15)
        model.finishRate()
        assertEquals("save failed", model.state.value.error)
        assertEquals(15, model.state.value.rate)
        assertTrue(model.state.value.pending.isEmpty())
        repo.failSave = false
        model.finishRate()
        assertNull(model.state.value.error)
        assertEquals(listOf(15), repo.savedRates)
    }

    @Test fun resumeReadsLatestPlaybackAndFollowPreferenceWithoutClobberingRateDraft() = runTest(dispatcher) {
        val repo = FakeRepository()
        val model = ReadAloudViewModel(repo, SavedStateHandle())
        runCurrent()
        model.changeRate(33)
        repo.playback = repo.playback.copy(paused = false, minute = 7)
        model.refreshRuntime()
        assertFalse(model.state.value.paused)
        assertEquals(7, model.state.value.timer)
        assertEquals(33, model.state.value.rate)
    }

    @Test fun leaseCannotDecrementRejectedOrAlreadyReleasedDialog() {
        val lease = ReadAloudDialogLease()
        assertFalse(lease.acquire(1))
        assertFalse(lease.release())
        assertTrue(lease.acquire(0))
        assertFalse(lease.acquire(1))
        assertTrue(lease.release())
        assertFalse(lease.release())
        assertTrue(lease.acquire(0))
    }

    private class FakeRepository : ReadAloudControlRepository {
        var prefs = ReadAloudControlPreferences(false, 5, 0)
        var playback = ReadAloudControlRuntime(true, 0, 0)
        var failSave = false
        var engine: suspend () -> String = { "Fake engine" }
        val savedRates = mutableListOf<Int>()
        val savedTimers = mutableListOf<Int>()
        override fun preferences() = prefs
        override fun runtime() = playback
        override fun saveFollowSystem(follow: Boolean) { if (failSave) error("save failed"); prefs = prefs.copy(followSystem = follow) }
        override fun saveRate(rate: Int) { if (failSave) error("save failed"); savedRates += rate; prefs = prefs.copy(rate = rate) }
        override fun saveDefaultTimer(minute: Int) { if (failSave) error("save failed"); savedTimers += minute; prefs = prefs.copy(defaultTimer = minute) }
        override suspend fun engineName() = engine()
    }
}
