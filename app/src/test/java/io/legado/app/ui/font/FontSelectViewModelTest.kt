package io.legado.app.ui.font

import android.graphics.Typeface
import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.FontEntry
import io.legado.app.data.repository.FontLoadResult
import io.legado.app.data.repository.FontSelectionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FontSelectViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private class Repository : FontSelectionRepository {
        var folder: String? = null
        val entry = FontEntry("/font/test.ttf", "file:///font/test.ttf", "test.ttf", true)
        var loader: suspend (String?) -> FontLoadResult = { FontLoadResult(listOf(entry)) }
        var importAction: suspend (String) -> Unit = {}
        var imports = 0
        val system = mutableListOf<Int>()
        override fun storedFolder() = folder
        override fun storeFolder(folder: String) { this.folder = folder }
        override suspend fun load(folder: String?) = loader(folder)
        override suspend fun importFont(uri: String) { imports++; importAction(uri) }
        override suspend fun preview(entry: FontEntry): Typeface? = null
        override fun selectSystemTypeface(index: Int) { system += index }
    }
    @Test fun listingAndSelectionReturnExactPathOnlyOnce() = runTest(dispatcher) {
        val repository = Repository()
        val model = FontSelectViewModel(repository, SavedStateHandle())
        model.load(); runCurrent()
        assertEquals(listOf(repository.entry), model.state.value.entries)
        model.select("not-listed")
        assertNull(model.state.value.selectedPath)
        model.select(repository.entry.path)
        assertEquals(repository.entry.path, model.state.value.selectedPath)
        model.defaultFont(false)
        assertEquals(repository.entry.path, model.state.value.selectedPath)
        model.selectionHandled()
        model.select(repository.entry.path)
        assertTrue(model.state.value.finished)
        assertNull(model.state.value.selectedPath)
    }
    @Test fun defaultInheritanceDoesNotChangeGlobalSystemFontAndPendingEmptyPathRestores() = runTest(dispatcher) {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = FontSelectViewModel(repository, handle)
        model.defaultFont(false)
        assertEquals("", model.state.value.selectedPath)
        assertTrue(repository.system.isEmpty())
        val restored = FontSelectViewModel(repository, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals("", restored.state.value.selectedPath)
    }
    @Test fun systemPickerRestoresCancelsAndPersistsExactChoice() = runTest(dispatcher) {
        val repository = Repository()
        val handle = SavedStateHandle()
        val model = FontSelectViewModel(repository, handle)
        model.defaultFont(true)
        val restored = FontSelectViewModel(repository, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertTrue(restored.state.value.systemPicker)
        restored.selectSystemTypeface(100)
        assertTrue(repository.system.isEmpty())
        restored.cancelSystemPicker()
        assertTrue(repository.system.isEmpty())
        restored.defaultFont(true)
        restored.selectSystemTypeface(2)
        restored.selectSystemTypeface(2)
        assertEquals(listOf(2), repository.system)
        assertEquals("", restored.state.value.selectedPath)
    }
    @Test fun unavailableEmptyFolderPromptsOnlyUntilPickerLaunchIsAcknowledged() = runTest(dispatcher) {
        val repository = Repository()
        repository.loader = { FontLoadResult(emptyList(), unavailableFolder = true) }
        val handle = SavedStateHandle()
        val model = FontSelectViewModel(repository, handle)
        model.load("content://missing", openWhenEmpty = true); runCurrent()
        assertTrue(model.state.value.openFolder)
        model.folderOpened()
        assertFalse(model.state.value.openFolder)
        model.load("content://missing", openWhenEmpty = false); runCurrent()
        assertFalse(model.state.value.openFolder)
    }
    @Test fun accessibleEmptyExternalFolderDoesNotForceAnotherPicker() = runTest(dispatcher) {
        val repository = Repository()
        repository.loader = { FontLoadResult(emptyList()) }
        val model = FontSelectViewModel(repository, SavedStateHandle())
        model.load("content://empty", openWhenEmpty = true); runCurrent()
        assertFalse(model.state.value.openFolder)
    }
    @Test fun newestFolderWinsEvenWhenOldLoaderIgnoresCancellation() = runTest(dispatcher) {
        val repository = Repository()
        val pending = CompletableDeferred<FontLoadResult>()
        repository.loader = { folder -> if (folder == "old") withContext(NonCancellable) { pending.await() }
            else FontLoadResult(listOf(repository.entry)) }
        val model = FontSelectViewModel(repository, SavedStateHandle())
        model.load("old"); runCurrent()
        model.load("new"); runCurrent()
        pending.complete(FontLoadResult(emptyList())); runCurrent()
        assertEquals(listOf(repository.entry), model.state.value.entries)
        assertFalse(model.state.value.loading)
    }
    @Test fun importIsSingleFlightAndRefreshesListingAfterSuccess() = runTest(dispatcher) {
        val repository = Repository()
        val pending = CompletableDeferred<Unit>()
        repository.importAction = { pending.await() }
        val model = FontSelectViewModel(repository, SavedStateHandle())
        model.importFont("content://font"); model.importFont("content://font"); runCurrent()
        assertEquals(1, repository.imports)
        assertTrue(model.state.value.importing)
        pending.complete(Unit); runCurrent()
        assertFalse(model.state.value.importing)
        assertTrue(model.state.value.importSucceeded)
        assertEquals(listOf(repository.entry), model.state.value.entries)
    }
    @Test fun invalidImportShowsErrorButLeavesPreviouslyLoadedFontsAvailable() = runTest(dispatcher) {
        val repository = Repository()
        repository.importAction = { throw IllegalArgumentException("invalid") }
        val model = FontSelectViewModel(repository, SavedStateHandle())
        model.load(); runCurrent()
        model.importFont("content://bad"); runCurrent()
        assertTrue(model.state.value.invalidImport)
        assertEquals("invalid", model.state.value.error)
        assertEquals(listOf(repository.entry), model.state.value.entries)
        assertFalse(model.state.value.importing)
    }
}
