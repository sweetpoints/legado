package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.cover.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CoverFontSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun clear() { Dispatchers.resetMain() }
    private fun own(vm: CoverFontSettingsViewModel) = ViewModelStore().apply { put("vm", vm) }
    private fun TestScope.ready(vm: CoverFontSettingsViewModel) { runCurrent(); assertFalse(vm.state.value.loading); assertFalse(vm.state.value.failed) }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun disabledSizeCannotOpenAndEnablingDoesNotOverwriteFourPercentValues() = runTest(dispatcher) {
        val repo = Repo(); val vm = CoverFontSettingsViewModel(repo, Drafts(), SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.edit(CoverFontSize.TitleLarge); assertNull(vm.state.value.editing)
            vm.boolean(CoverFontSwitch.CustomSize, true); runCurrent(); assertEquals(100, vm.state.value.settings!!.sizes.getValue(CoverFontSize.AuthorSmall))
            vm.edit(CoverFontSize.AuthorSmall); assertEquals(CoverFontSize.AuthorSmall, vm.state.value.editing)
        } finally { owner.clear() }
    }
    @Test fun percentageDraftClampsRestoresWithoutWritingAndCancelPreservesActualPercent() = runTest(dispatcher) {
        val repo = Repo().apply { enable() }; val saved = SavedStateHandle(); val vm = CoverFontSettingsViewModel(repo, Drafts(), saved); val owner = own(vm)
        try { ready(vm); vm.edit(CoverFontSize.TitleLarge); vm.number(999); assertEquals(200, vm.state.value.number)
            val restored = CoverFontSettingsViewModel(repo, Drafts(), copy(saved)); val second = own(restored)
            try { ready(restored); assertEquals(CoverFontSize.TitleLarge, restored.state.value.editing); assertEquals(200, restored.state.value.number)
                assertTrue(repo.calls.isEmpty()); restored.dismiss(); restored.confirm(); runCurrent(); assertTrue(repo.calls.isEmpty())
            } finally { second.clear() }
        } finally { owner.clear() }
    }
    @Test fun explicitConfirmAndDefaultWriteOnlySelectedSizeAndCloseOnlyAfterSuccess() = runTest(dispatcher) {
        val repo = Repo().apply { enable() }; val vm = CoverFontSettingsViewModel(repo, Drafts(), SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.edit(CoverFontSize.AuthorLarge); vm.number(0); repo.failSize = true; vm.confirm(); runCurrent()
            assertEquals(50, vm.state.value.number); assertEquals(CoverFontSize.AuthorLarge, vm.state.value.editing); assertNotNull(vm.state.value.error)
            repo.failSize = false; vm.retry(); runCurrent(); assertNull(vm.state.value.editing); assertEquals(50, repo.values.value.sizes.getValue(CoverFontSize.AuthorLarge))
            vm.edit(CoverFontSize.AuthorLarge); vm.number(173); vm.confirm(true); runCurrent(); assertEquals(100, repo.values.value.sizes.getValue(CoverFontSize.AuthorLarge))
            assertEquals(100, repo.values.value.sizes.getValue(CoverFontSize.TitleSmall))
        } finally { owner.clear() }
    }
    @Test fun earlyDefaultCallbackClearsRestoredLaunchAndIsNotDroppedBeforeInitialization() = runTest(dispatcher) {
        val repo = Repo(); val vm = CoverFontSettingsViewModel(repo, Drafts(), SavedStateHandle(mapOf("fontEvent" to "restored"))); val owner = own(vm)
        try { vm.selectFont(""); ready(vm); assertEquals(listOf("font:"), repo.calls)
            assertNull(vm.state.value.fontEvent); assertFalse(vm.consumeFontEvent("restored"))
        } finally { owner.clear() }
    }
    @Test fun earlyFontSurvivesInitializationFailureThenProcessesAfterExplicitRetry() = runTest(dispatcher) {
        val repo = Repo(); val drafts = Drafts().apply { failOpen = true }; val vm = CoverFontSettingsViewModel(repo, drafts, SavedStateHandle()); val owner = own(vm)
        try { vm.selectFont("content:font"); runCurrent(); assertTrue(vm.state.value.failed); assertTrue(repo.calls.isEmpty())
            drafts.failOpen = false; vm.retry(); runCurrent(); assertEquals(listOf("font:content:font"), repo.calls)
        } finally { owner.clear() }
    }
    @Test fun privateLargeFontInputRestoreRequiresRetryAndClearsWithRevisionNewerThanDisk() = runTest(dispatcher) {
        val path = "content:" + "large-font".repeat(100000); val revision = System.nanoTime() + 1_000_000_000_000
        val drafts = Drafts().apply { value = CoverFontDraft(CoverFontInput("pending", path), revision) }
        val saved = SavedStateHandle(); val repo = Repo(); val vm = CoverFontSettingsViewModel(repo, drafts, saved); val owner = own(vm)
        try { ready(vm); assertTrue(vm.state.value.fontRetry); assertTrue(repo.calls.isEmpty()); assertFalse(saved.keys().any { saved.get<Any?>(it) == path })
            vm.retry(); runCurrent(); assertEquals(listOf("font:$path"), repo.calls); assertNull(drafts.value.input); assertTrue(drafts.value.revision > revision)
        } finally { owner.clear() }
    }
    @Test fun durableInputWriteMustSucceedBeforeInstallAndReceiptFailureRetriesWithoutReinstalling() = runTest(dispatcher) {
        val repo = Repo(); val drafts = Drafts(); val vm = CoverFontSettingsViewModel(repo, drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); drafts.failWrite = true; vm.selectFont("chosen.ttf"); runCurrent(); assertTrue(repo.calls.isEmpty())
            drafts.failWrite = false; drafts.failClear = true; vm.retry(); runCurrent(); assertEquals(listOf("font:chosen.ttf"), repo.calls)
            drafts.failClear = false; vm.retry(); runCurrent(); assertEquals(1, repo.calls.size); assertNull(drafts.value.input)
        } finally { owner.clear() }
    }
    @Test fun lateNonCooperativeFontErrorCannotPublishAfterStopAndReleaseFencesOwner() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Repo().apply { fontGate = gate }; val drafts = Drafts(); val vm = CoverFontSettingsViewModel(repo, drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.selectFont("old.ttf"); runCurrent(); val before = vm.state.value; vm.stop(); gate.complete(Unit); runCurrent(); assertEquals(before, vm.state.value)
            vm.release(); assertTrue(drafts.released)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun restoredFontNavigationLaunchConsumesExactlyOnceWithoutWritingPreferences() = runTest(dispatcher) {
        val repo = Repo(); val vm = CoverFontSettingsViewModel(repo, Drafts(), SavedStateHandle(mapOf("fontEvent" to "launch"))); val owner = own(vm)
        try { ready(vm); assertEquals("launch", vm.state.value.fontEvent); assertTrue(vm.consumeFontEvent("launch")); assertFalse(vm.consumeFontEvent("launch")); assertTrue(repo.calls.isEmpty()) }
        finally { owner.clear() }
    }
    private class Drafts : CoverFontDraftRepository {
        var value = CoverFontDraft(); var failOpen = false; var failWrite = false; var failClear = false; var released = false
        override suspend fun open(session: String): CoverFontDraft { check(!released); if (failOpen) error("open failed"); return value }
        override suspend fun write(session: String, draft: CoverFontDraft) { check(!released); if (failWrite || failClear && draft.input == null) error("write failed"); if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) { released = true }
    }
    private class Repo : CoverFontSettingsRepository {
        val values = MutableStateFlow(CoverFontSettingsSnapshot()); val calls = mutableListOf<String>(); var failSize = false; var fontGate: CompletableDeferred<Unit>? = null
        fun enable() { values.value = values.value.copy(switches = values.value.switches + (CoverFontSwitch.CustomSize to true)) }
        override fun observe(): Flow<CoverFontSettingsSnapshot> = values
        override suspend fun load() = values.value
        override suspend fun boolean(key: CoverFontSwitch, value: Boolean) { calls += "bool:${key.name}:$value"; values.value = values.value.copy(switches = values.value.switches + (key to value)) }
        override suspend fun size(key: CoverFontSize, value: Int) { calls += "size:${key.name}:$value"; if (failSize) error("size failed"); values.value = values.value.copy(sizes = values.value.sizes + (key to value)) }
        override suspend fun font(path: String) { calls += "font:$path"; fontGate?.let { withContext(NonCancellable) { it.await(); error("late failure") } }; values.value = values.value.copy(fontPath = path) }
    }
}
