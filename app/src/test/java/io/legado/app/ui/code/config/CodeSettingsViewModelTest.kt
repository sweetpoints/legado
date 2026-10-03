package io.legado.app.ui.code.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.preferences.CodeSettingsPreferences
import io.legado.app.data.preferences.CodeSettingsSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodeSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsPreferencesWithoutWrites() =
        runTest(dispatcher) {
            val prefs = Fake(CodeSettingsSnapshot(23, false, 7))
            val model = CodeSettingsViewModel(prefs, SavedStateHandle())
            runCurrent()
            assertEquals(23, model.state.value.font)
            assertFalse(model.state.value.autoComplete)
            assertEquals(7, model.state.value.nonPrintable)
            assertFalse(model.state.value.loading)
            assertNull(model.onDismiss(false))
            assertTrue(prefs.writes.isEmpty())
        }

    @Test
    fun fontAndAutoCompleteWriteImmediatelyButFlagsWaitForClose() =
        runTest(dispatcher) {
            val prefs = Fake()
            val model = CodeSettingsViewModel(prefs, SavedStateHandle())
            runCurrent()
            model.setFont(21)
            model.setAutoComplete(false)
            model.toggleFlag(1)
            assertEquals(listOf("font:21", "auto:false"), prefs.writes)
            assertEquals(1, model.onDismiss(false))
            assertEquals(listOf("font:21", "auto:false", "flags:1"), prefs.writes)
            assertNull(model.onDismiss(false))
        }

    @Test
    fun fontRangeAndDefaultArePreserved() =
        runTest(dispatcher) {
            val model = CodeSettingsViewModel(Fake(), SavedStateHandle())
            runCurrent()
            model.setFont(0)
            assertEquals(9, model.state.value.font)
            model.setFont(99)
            assertEquals(36, model.state.value.font)
            model.setFont(16)
            assertEquals(16, model.state.value.font)
        }

    @Test
    fun togglingSameFlagBackDoesNotWriteOnDismiss() =
        runTest(dispatcher) {
            val prefs = Fake()
            val model = CodeSettingsViewModel(prefs, SavedStateHandle())
            runCurrent()
            model.toggleFlag(2)
            model.toggleFlag(2)
            assertNull(model.onDismiss(false))
            assertTrue(prefs.writes.isEmpty())
        }

    @Test
    fun configurationChangeKeepsDraftAndPickerWithoutSavingFlags() =
        runTest(dispatcher) {
            val prefs = Fake()
            val handle = SavedStateHandle()
            val model = CodeSettingsViewModel(prefs, handle)
            runCurrent()
            model.toggleFlag(4)
            model.showFontPicker(true)
            assertNull(model.onDismiss(true))
            assertTrue(prefs.writes.isEmpty())
            val restored =
                CodeSettingsViewModel(
                    prefs,
                    SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }),
                )
            runCurrent()
            assertEquals(4, restored.state.value.nonPrintable)
            assertTrue(restored.state.value.fontPicker)
            assertEquals(4, restored.onDismiss(false))
            assertEquals(listOf("flags:4"), prefs.writes)
        }

    @Test
    fun lateLoadDoesNotOverwriteEdits() =
        runTest(dispatcher) {
            val deferred = CompletableDeferred<CodeSettingsSnapshot>()
            val prefs = Fake().apply { read = { deferred.await() } }
            val model = CodeSettingsViewModel(prefs, SavedStateHandle())
            runCurrent()
            model.setFont(27)
            model.setAutoComplete(false)
            model.toggleFlag(8)
            deferred.complete(CodeSettingsSnapshot(11, true, 0))
            runCurrent()
            assertEquals(27, model.state.value.font)
            assertFalse(model.state.value.autoComplete)
            assertEquals(8, model.state.value.nonPrintable)
        }

    @Test
    fun closeBeforeLoadDoesNotWriteDefaultFlags() =
        runTest(dispatcher) {
            val prefs = Fake()
            val model = CodeSettingsViewModel(prefs, SavedStateHandle())
            assertNull(model.onDismiss(false))
            runCurrent()
            assertTrue(prefs.writes.isEmpty())
        }

    private class Fake(private val snapshot: CodeSettingsSnapshot = CodeSettingsSnapshot()) :
        CodeSettingsPreferences {
        val writes = mutableListOf<String>()
        var read: suspend () -> CodeSettingsSnapshot = { snapshot }

        override suspend fun load() = read()

        override fun saveFont(value: Int) {
            writes += "font:$value"
        }

        override fun saveAutoComplete(value: Boolean) {
            writes += "auto:$value"
        }

        override fun saveNonPrintable(value: Int) {
            writes += "flags:$value"
        }
    }
}
