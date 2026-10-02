package io.legado.app.ui.code.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.CodeThemePreferences
import io.legado.app.data.preferences.CodeThemeSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodeThemeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun loadsAllPreferencesWithoutWritingThem() = runTest(dispatcher) {
        val prefs = Fake(CodeThemeSnapshot(true, 3, 6)); val model = CodeThemeViewModel(prefs, SavedStateHandle())
        model.setSystemDark(true); runCurrent()
        assertEquals(6, model.state.value.selected); assertTrue(model.state.value.automatic)
        assertTrue(prefs.themes.isEmpty()); assertTrue(prefs.automatic.isEmpty())
    }
    @Test fun selectionSavesDarkOnlyWhenAutomaticAndDark() = runTest(dispatcher) {
        val prefs = Fake(CodeThemeSnapshot(true, 1, 2)); val model = CodeThemeViewModel(prefs, SavedStateHandle()); runCurrent()
        model.setSystemDark(true); model.select(7)
        assertEquals(listOf(true to 7), prefs.themes); assertEquals(1, model.state.value.light)
        model.setAutomatic(false); assertEquals(1, model.state.value.selected)
        model.select(4); assertEquals(listOf(true to 7, false to 4), prefs.themes)
        assertEquals(7, model.state.value.dark)
    }
    @Test fun enablingAutomaticRestoresPreviouslySavedDarkChoice() = runTest(dispatcher) {
        val model = CodeThemeViewModel(Fake(CodeThemeSnapshot(false, 3, 6)), SavedStateHandle()); runCurrent()
        model.setSystemDark(true); assertEquals(3, model.state.value.selected)
        model.setAutomatic(true); assertEquals(6, model.state.value.selected)
        model.setAutomatic(false); assertEquals(3, model.state.value.selected)
    }
    @Test fun environmentChangeSelectsCorrectSlotWithoutWriting() = runTest(dispatcher) {
        val prefs = Fake(CodeThemeSnapshot(true, 1, 4)); val model = CodeThemeViewModel(prefs, SavedStateHandle()); runCurrent()
        model.setSystemDark(true); assertEquals(4, model.state.value.selected)
        model.setSystemDark(false); assertEquals(1, model.state.value.selected)
        assertTrue(prefs.themes.isEmpty())
    }
    @Test fun savedStateRestoresSelectionsWithoutNewReadsOrWrites() = runTest(dispatcher) {
        val prefs = Fake(); val saved = SavedStateHandle(); val model = CodeThemeViewModel(prefs, saved); runCurrent()
        model.select(3); model.setAutomatic(true); model.setSystemDark(true); model.select(6)
        val restored = CodeThemeViewModel(prefs, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })); runCurrent()
        restored.setSystemDark(true)
        assertEquals(6, restored.state.value.selected); assertEquals(1, prefs.reads)
        assertEquals(listOf(false to 3, true to 6), prefs.themes)
    }
    @Test fun lateLoadingDoesNotOverwriteUserSelections() = runTest(dispatcher) {
        val deferred = CompletableDeferred<CodeThemeSnapshot>()
        val prefs = Fake().apply { read = { deferred.await() } }
        val model = CodeThemeViewModel(prefs, SavedStateHandle()); runCurrent()
        model.select(5); model.setAutomatic(true)
        deferred.complete(CodeThemeSnapshot(false, 1, 2)); runCurrent()
        assertEquals(5, model.state.value.light); assertEquals(2, model.state.value.dark)
        assertTrue(model.state.value.automatic)
    }
    @Test fun selectionsClampToAvailableEightThemes() = runTest(dispatcher) {
        val model = CodeThemeViewModel(Fake(), SavedStateHandle()); runCurrent()
        model.select(-1); assertEquals(0, model.state.value.selected)
        model.select(9); assertEquals(7, model.state.value.selected)
    }
    private class Fake(private val snapshot: CodeThemeSnapshot = CodeThemeSnapshot()) : CodeThemePreferences {
        val themes = mutableListOf<Pair<Boolean, Int>>()
        val automatic = mutableListOf<Boolean>()
        var reads = 0
        var read: suspend () -> CodeThemeSnapshot = { snapshot }
        override suspend fun load(): CodeThemeSnapshot { reads++; return read() }
        override fun saveAutomatic(value: Boolean) { automatic += value }
        override fun saveTheme(dark: Boolean, index: Int) { themes += dark to index }
    }
}
