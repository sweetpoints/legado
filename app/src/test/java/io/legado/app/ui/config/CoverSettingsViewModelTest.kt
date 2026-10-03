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
class CoverSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun own(vm: CoverSettingsViewModel) = ViewModelStore().apply { put("vm", vm) }
    private fun TestScope.ready(vm: CoverSettingsViewModel) { runCurrent(); assertFalse(vm.state.value.loading); assertFalse(vm.state.value.failed) }
    private fun savedCopy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun disablingDayNameKeepsAuthorStoredAndNightIndependentAndBlocksDisabledToggle() = runTest(dispatcher) {
        val repo = Repo(); val vm = CoverSettingsViewModel(repo, Inputs(), SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.boolean(CoverSettingSwitch.DayName, false); runCurrent(); vm.boolean(CoverSettingSwitch.DayAuthor, false); runCurrent()
            assertEquals(listOf("bool:DayName:false"), repo.calls); assertTrue(vm.state.value.settings!!.switches.getValue(CoverSettingSwitch.DayAuthor))
            vm.boolean(CoverSettingSwitch.NightAuthor, false); runCurrent(); assertFalse(vm.state.value.settings!!.switches.getValue(CoverSettingSwitch.NightAuthor))
        } finally { owner.clear() }
    }
    @Test fun allFourPickerTicketsRestoreMissingResultMetadataAndRejectMismatchedOrDuplicateCallbacks() = runTest(dispatcher) {
        CoverSettingImage.entries.forEach { key ->
            val repo = Repo(); val saved = SavedStateHandle(mapOf("picker" to "launch", "pickerKey" to key.name))
            val vm = CoverSettingsViewModel(repo, Inputs(), saved); val owner = own(vm)
            try { vm.pickedImage("content:wrong", CoverSettingImage.entries.first { it != key }.key)
                vm.pickedImage("content:${key.name}"); vm.pickedImage("content:duplicate"); ready(vm)
                assertEquals(listOf("image:${key.name}:content:${key.name}"), repo.calls); assertNull(vm.state.value.picker)
                assertNull(saved.get<String>("pickerKey")); assertNull(saved.get<String>("picker"))
            } finally { owner.clear() }
        }
    }
    @Test fun pickerAndNavigationConsumeExactlyOnceAcrossRestorationAndCancellationClearsTicket() = runTest(dispatcher) {
        val repo = Repo(); val inputs = Inputs(); val saved = SavedStateHandle(); val vm = CoverSettingsViewModel(repo, inputs, saved); val owner = own(vm)
        try { ready(vm); vm.picker(CoverSettingImage.RecordNight); vm.destination(CoverDestination.Font)
            val other = CoverSettingsViewModel(repo, inputs, savedCopy(saved)); val second = own(other)
            try { ready(other); val picker = other.state.value.picker!!; val nav = other.state.value.navigation!!
                assertTrue(other.consumePicker(picker.id)); assertFalse(other.consumePicker(picker.id))
                assertTrue(other.consumeNavigation(nav.id)); assertFalse(other.consumeNavigation(nav.id))
                other.pickedImage(null); other.pickedImage("content:late"); runCurrent(); assertTrue(repo.calls.isEmpty())
            } finally { second.clear() }
        } finally { owner.clear() }
    }
    @Test fun privateInputRestoreRequiresExplicitRetryAndUsesDiskRevisionBaselineWithoutBundlePayload() = runTest(dispatcher) {
        val payload = "content:" + "large".repeat(200000); val revision = System.nanoTime() + 1_000_000_000_000
        val inputs = Inputs().apply { draft = CoverImageDraft(CoverImageInput("receipt", CoverSettingImage.RecordDay, payload), revision) }
        val saved = SavedStateHandle(); val repo = Repo(); val vm = CoverSettingsViewModel(repo, inputs, saved); val owner = own(vm)
        try { ready(vm); assertTrue(vm.state.value.imageRetry); assertTrue(repo.calls.isEmpty()); assertFalse(saved.keys().any { saved.get<Any?>(it) == payload })
            vm.retry(); runCurrent(); assertEquals(1, repo.calls.size); assertNull(inputs.draft.input); assertTrue(inputs.draft.revision > revision)
        } finally { owner.clear() }
    }
    @Test fun earlyResultSurvivesInitializationFailureAndReceiptWriteRetryDoesNotInstallImageTwice() = runTest(dispatcher) {
        val repo = Repo(); val inputs = Inputs().apply { failOpen = true; failClear = true }
        val vm = CoverSettingsViewModel(repo, inputs, SavedStateHandle(mapOf("pickerKey" to CoverSettingImage.Night.name))); val owner = own(vm)
        try { vm.pickedImage("content:night"); runCurrent(); assertTrue(vm.state.value.failed); assertTrue(repo.calls.isEmpty())
            inputs.failOpen = false; vm.retry(); runCurrent(); assertEquals(listOf("image:Night:content:night"), repo.calls); assertTrue(vm.state.value.imageRetry)
            inputs.failClear = false; vm.retry(); runCurrent(); assertEquals(1, repo.calls.size); assertNull(inputs.draft.input)
        } finally { owner.clear() }
    }
    @Test fun pendingInputWriteFailureBlocksInstallAndRetryKeepsExactTarget() = runTest(dispatcher) {
        val inputs = Inputs(); val repo = Repo(); val vm = CoverSettingsViewModel(repo, inputs, SavedStateHandle(mapOf("pickerKey" to CoverSettingImage.Day.name))); val owner = own(vm)
        try { ready(vm); inputs.failWrite = true; vm.pickedImage("content:day"); runCurrent(); assertTrue(vm.state.value.imageRetry); assertTrue(repo.calls.isEmpty())
            inputs.failWrite = false; vm.retry(); runCurrent(); assertEquals(listOf("image:Day:content:day"), repo.calls)
        } finally { owner.clear() }
    }
    @Test fun stoppedOwnerCannotPublishNonCooperativeLateFailureAndReleasesOnlyOwnedSession() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Repo().apply { imageGate = gate }; val inputs = Inputs()
        val vm = CoverSettingsViewModel(repo, inputs, SavedStateHandle(mapOf("pickerKey" to CoverSettingImage.Day.name))); val owner = own(vm)
        try { ready(vm); vm.pickedImage("content:pending"); runCurrent(); val state = vm.state.value; vm.stop(); gate.complete(Unit); runCurrent()
            assertEquals(state, vm.state.value); vm.release(); assertTrue(inputs.released)
        } finally { gate.complete(Unit); owner.clear() }
    }
    private class Inputs : CoverImageInputRepository {
        var draft = CoverImageDraft(); var failOpen = false; var failWrite = false; var failClear = false; var released = false
        override suspend fun open(session: String): CoverImageDraft { if (failOpen) error("open failed"); check(!released); return draft }
        override suspend fun write(session: String, draft: CoverImageDraft) { check(!released); if (failWrite || failClear && draft.input == null) error("write failed"); if (draft.revision >= this.draft.revision) this.draft = draft }
        override suspend fun release(session: String) { released = true }
    }
    private class Repo : CoverSettingsRepository {
        val values = MutableStateFlow(CoverSettingsSnapshot()); val calls = mutableListOf<String>(); var imageGate: CompletableDeferred<Unit>? = null
        override fun observe(): Flow<CoverSettingsSnapshot> = values
        override suspend fun load() = values.value
        override suspend fun boolean(key: CoverSettingSwitch, value: Boolean) { calls += "bool:${key.name}:$value"; values.value = values.value.copy(switches = values.value.switches + (key to value)) }
        override suspend fun image(key: CoverSettingImage, uri: String?) { calls += "image:${key.name}:$uri"; imageGate?.let { withContext(NonCancellable) { it.await(); error("late failure") } }
            values.value = values.value.copy(images = values.value.images + (key to uri.orEmpty())) }
    }
}
