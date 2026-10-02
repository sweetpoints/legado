package io.legado.app.ui.book.manga.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.MangaEpaperPreferences
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
class MangaEpaperViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun unchangedCloseRetainsStoredThresholdWithoutWritingDefault() = runTest(dispatcher) {
        val preferences = FakePreferences(91)
        val model = MangaEpaperViewModel(preferences, SavedStateHandle())
        runCurrent()
        assertEquals(91, model.state.value.threshold)
        model.onDismiss(false)
        assertTrue(preferences.saved.isEmpty())
        assertEquals(91, preferences.stored)
    }

    @Test fun closeBeforeLoadNeverWritesDefaultOrLateResult() = runTest(dispatcher) {
        val pending = CompletableDeferred<Int>()
        val preferences = FakePreferences(207).apply {
            read = { withContext(NonCancellable) { pending.await() } }
        }
        val handle = SavedStateHandle()
        val model = MangaEpaperViewModel(preferences, handle)
        runCurrent()
        model.onDismiss(false)
        pending.complete(207)
        runCurrent()
        assertTrue(preferences.saved.isEmpty())
        assertTrue(handle.keys().isEmpty())
    }

    @Test fun editsClampToByteRangeAndClosingSavesOnlyOnce() = runTest(dispatcher) {
        val preferences = FakePreferences(91)
        val model = MangaEpaperViewModel(preferences, SavedStateHandle())
        runCurrent()
        model.setThreshold(-20)
        assertEquals(0, model.state.value.threshold)
        model.setThreshold(256)
        assertEquals(255, model.state.value.threshold)
        model.setThreshold(173)
        assertTrue(preferences.saved.isEmpty())
        model.onDismiss(false)
        model.onDismiss(false)
        model.setThreshold(200)
        assertEquals(listOf(173), preferences.saved)
        assertEquals(173, model.state.value.threshold)
    }

    @Test fun configurationChangePreservesDraftWithoutPersistingAndRestoreSkipsRead() = runTest(dispatcher) {
        val preferences = FakePreferences(91)
        val handle = SavedStateHandle()
        val model = MangaEpaperViewModel(preferences, handle)
        runCurrent()
        model.setThreshold(212)
        model.onDismiss(true)
        assertTrue(preferences.saved.isEmpty())
        val restored = MangaEpaperViewModel(preferences,
            SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        runCurrent()
        assertEquals(212, restored.state.value.threshold)
        assertFalse(restored.state.value.isLoading)
        assertEquals(1, preferences.readCount)
        restored.onDismiss(false)
        assertEquals(listOf(212), preferences.saved)
    }

    @Test fun lateLoadDoesNotOverrideInputOrSavedDraft() = runTest(dispatcher) {
        val pending = CompletableDeferred<Int>()
        val preferences = FakePreferences(91).apply { read = { pending.await() } }
        val handle = SavedStateHandle()
        val model = MangaEpaperViewModel(preferences, handle)
        runCurrent()
        model.setThreshold(220)
        pending.complete(91)
        runCurrent()
        assertEquals(220, model.state.value.threshold)
        val restored = MangaEpaperViewModel(preferences,
            SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(220, restored.state.value.threshold)
        model.onDismiss(false)
        assertEquals(listOf(220), preferences.saved)
    }

    @Test fun failedReadAllowsRetryAndDoesNotPersistFallback() = runTest(dispatcher) {
        val preferences = FakePreferences(91).apply { read = { error("read failed") } }
        val model = MangaEpaperViewModel(preferences, SavedStateHandle())
        runCurrent()
        assertEquals("read failed", model.state.value.error)
        preferences.read = { 91 }
        model.load()
        runCurrent()
        assertEquals(91, model.state.value.threshold)
        assertNull(model.state.value.error)
        model.onDismiss(false)
        assertTrue(preferences.saved.isEmpty())
    }

    @Test fun restoredUneditedValueDoesNotBecomeASaveRequest() = runTest(dispatcher) {
        val preferences = FakePreferences(91)
        val handle = SavedStateHandle()
        MangaEpaperViewModel(preferences, handle)
        runCurrent()
        val restored = MangaEpaperViewModel(preferences,
            SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        restored.onDismiss(false)
        assertTrue(preferences.saved.isEmpty())
        assertEquals(1, preferences.readCount)
    }

    private class FakePreferences(var stored: Int) : MangaEpaperPreferences {
        var read: suspend () -> Int = { stored }
        var readCount = 0
        val saved = mutableListOf<Int>()
        override suspend fun loadThreshold(): Int { readCount++; return read() }
        override fun saveThreshold(threshold: Int) { stored = threshold; saved += threshold }
    }
}
