package io.legado.app.ui.config

import androidx.lifecycle.*
import io.legado.app.data.preferences.*
import io.legado.app.model.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun clear() { Dispatchers.resetMain() }
    private fun own(vm: BackupSettingsViewModel) = ViewModelStore().apply { put("vm", vm) }
    private fun TestScope.ready(vm: BackupSettingsViewModel) { runCurrent(); assertFalse(vm.state.value.loading); assertFalse(vm.state.value.failed) }
    @Test fun privateLargePasswordDraftRestoresAndCancelClearsWithoutSavingPreferenceOrBundlePayload() = runTest(dispatcher) {
        val payload = "synthetic".repeat(100000); val drafts = Drafts().apply { value = BackupSettingsDraft(99, BackupForm.Password, payload) }
        val repo = Repo(); val saved = SavedStateHandle(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, saved); val owner = own(vm)
        try { ready(vm); assertEquals(payload, vm.state.value.draft!!.text); assertTrue(repo.calls.isEmpty()); assertFalse(saved.keys().any { saved.get<Any?>(it) == payload })
            vm.dismiss(); runCurrent(); assertNull(vm.state.value.draft!!.form); assertEquals("", drafts.value.text); assertTrue(repo.calls.isEmpty())
        } finally { owner.clear() }
    }
    @Test fun automaticFormThreeFieldsAreDraftUntilValidConfirmAndEmptyDaysAreRestored() = runTest(dispatcher) {
        val repo = Repo(); val drafts = Drafts(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.automaticForm(); vm.automaticEnabled(false); vm.automaticWebDav(false); vm.interval(""); runCurrent()
            assertEquals("", drafts.value.intervalText); vm.confirm(); runCurrent(); assertTrue(vm.state.value.invalidInterval); assertTrue(repo.calls.isEmpty())
            vm.interval("0"); vm.confirm(); runCurrent(); assertTrue(repo.calls.isEmpty()); vm.interval("37"); vm.confirm(); runCurrent()
            assertEquals(AutoBackupSettings(false, false, 37), repo.values.value.automatic); assertEquals(listOf("automatic"), repo.calls); assertNull(vm.state.value.draft!!.form)
        } finally { owner.clear() }
    }
    @Test fun normalConnectionAndLocalPasswordConfirmSaveExactlyOnceAndClearPrivatePayload() = runTest(dispatcher) {
        val repo = Repo(); val drafts = Drafts(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.textForm(BackupSettingText.Directory); vm.text("nested-dir"); runCurrent(); assertTrue(repo.calls.isEmpty())
            vm.confirm(); runCurrent(); assertEquals("nested-dir", repo.values.value.texts.getValue(BackupSettingText.Directory)); assertEquals("", drafts.value.text)
            vm.localPasswordForm(); vm.text("synthetic-only"); vm.confirm(); runCurrent(); assertEquals(listOf("text:Directory", "local-password"), repo.calls)
            vm.confirm(); runCurrent(); assertEquals(2, repo.calls.size)
        } finally { owner.clear() }
    }
    @Test fun disabledProgressPlusDoesNotResetValueAndDefaultPathChangesOnlyPath() = runTest(dispatcher) {
        val repo = Repo().apply { values.value = values.value.copy(switches = values.value.switches + (BackupSettingSwitch.Progress to false) + (BackupSettingSwitch.ProgressPlus to true)) }
        val vm = BackupSettingsViewModel(repo, Choices(), Drafts(), SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.boolean(BackupSettingSwitch.ProgressPlus, false); runCurrent(); assertTrue(repo.calls.isEmpty())
            vm.path(null); runCurrent(); assertEquals(listOf("path"), repo.calls); assertTrue(vm.state.value.settings!!.switches.getValue(BackupSettingSwitch.ProgressPlus))
        } finally { owner.clear() }
    }
    @Test fun contentSelectionChangesLiveChoicesButSavesFileOnlyOnDismiss() = runTest(dispatcher) {
        val choices = Choices(); val drafts = Drafts(); val vm = BackupSettingsViewModel(Repo(), choices, drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.choicesForm(BackupChoiceGroup.Content); runCurrent(); vm.choice("books", false); runCurrent()
            assertFalse(choices.rows.first().checked); assertEquals(false, drafts.value.choices["books"]); assertEquals(0, choices.saves)
            vm.dismiss(); runCurrent(); assertEquals(1, choices.saves); assertNull(vm.state.value.draft!!.form)
        } finally { owner.clear() }
    }
    @Test fun restoredChoiceDraftRemainsVisibleAndClosingReappliesProcessLostLiveMapThenSaves() = runTest(dispatcher) {
        val choices = Choices(); val drafts = Drafts().apply { value = BackupSettingsDraft(form = BackupForm.Content, choices = mapOf("books" to false)) }
        val vm = BackupSettingsViewModel(Repo(), choices, drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); assertFalse(vm.state.value.choices.first().checked); assertTrue(choices.rows.first().checked)
            vm.dismiss(); runCurrent(); assertFalse(choices.rows.first().checked); assertEquals(1, choices.saves)
        } finally { owner.clear() }
    }
    @Test fun failedAcceptedFormClearBlocksUnsafeWritesAndRetryOnlyClearsReceiptWithoutSavingAgain() = runTest(dispatcher) {
        val repo = Repo(); val drafts = Drafts(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.textForm(BackupSettingText.Account); vm.text("fixture"); runCurrent(); drafts.failClear = true
            vm.confirm(); runCurrent(); assertEquals(listOf("text:Account"), repo.calls); assertTrue(vm.state.value.pendingCommit)
            vm.path("unsafe"); vm.boolean(BackupSettingSwitch.Latest, false); runCurrent(); assertEquals(1, repo.calls.size)
            drafts.failClear = false; vm.retry(); runCurrent(); assertEquals(1, repo.calls.size); assertFalse(vm.state.value.pendingCommit); assertNull(vm.state.value.draft!!.form)
        } finally { owner.clear() }
    }
    @Test fun initializationFailureNeverSavesBlankAndRetryRestoresDiskRevisionBaseline() = runTest(dispatcher) {
        val revision = System.nanoTime() + 1_000_000_000_000; val drafts = Drafts().apply { value = BackupSettingsDraft(revision, BackupForm.Url, "private-fixture"); failOpen = true }
        val repo = Repo(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, SavedStateHandle()); val owner = own(vm)
        try { runCurrent(); assertTrue(vm.state.value.failed); vm.confirm(); vm.path(null); runCurrent(); assertTrue(repo.calls.isEmpty())
            drafts.failOpen = false; vm.retry(); ready(vm); vm.text("new-fixture"); runCurrent(); assertTrue(drafts.value.revision > revision); assertEquals("new-fixture", drafts.value.text)
        } finally { owner.clear() }
    }
    @Test fun failedDraftWriteBlocksPreferenceCommitAndExplicitRetryCanPersistBeforeAccepting() = runTest(dispatcher) {
        val repo = Repo(); val drafts = Drafts(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.textForm(BackupSettingText.Password); drafts.failWrite = true; vm.text("synthetic-only"); runCurrent(); assertTrue(vm.state.value.draftFailed)
            vm.confirm(); runCurrent(); assertTrue(repo.calls.isEmpty()); drafts.failWrite = false; vm.retry(); runCurrent(); vm.confirm(); runCurrent()
            assertEquals(listOf("text:Password"), repo.calls)
        } finally { owner.clear() }
    }
    @Test fun stoppedOwnerCannotPublishNonCooperativeLateFailureAndReleaseKeepsOwnershipFence() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Repo().apply { operationGate = gate }; val drafts = Drafts(); val vm = BackupSettingsViewModel(repo, Choices(), drafts, SavedStateHandle()); val owner = own(vm)
        try { ready(vm); vm.path("selected"); runCurrent(); val before = vm.state.value; vm.stop(); gate.complete(Unit); runCurrent(); assertEquals(before, vm.state.value)
            vm.release(); assertTrue(drafts.released)
        } finally { gate.complete(Unit); owner.clear() }
    }
    private class Drafts : BackupSettingsDraftRepository {
        var value = BackupSettingsDraft(); var failOpen = false; var failWrite = false; var failClear = false; var released = false
        override suspend fun open(session: String): BackupSettingsDraft { check(!released); if (failOpen) error("open failed"); return value }
        override suspend fun write(session: String, draft: BackupSettingsDraft) { check(!released); if (failWrite || failClear && value.form != null && draft.form == null) error("write failed"); if (draft.revision >= value.revision) value = draft }
        override suspend fun release(session: String) { released = true }
    }
    private class Repo : BackupSettingsRepository {
        val values = MutableStateFlow(BackupSettingsSnapshot()); val calls = mutableListOf<String>(); var operationGate: CompletableDeferred<Unit>? = null
        override fun observe(): Flow<BackupSettingsSnapshot> = values
        override suspend fun load() = values.value
        override suspend fun text(key: BackupSettingText, value: String) { calls += "text:${key.name}"; values.value = values.value.copy(texts = values.value.texts + (key to value)) }
        override suspend fun boolean(key: BackupSettingSwitch, value: Boolean) { calls += "bool:${key.name}"; values.value = values.value.copy(switches = values.value.switches + (key to value)) }
        override suspend fun automatic(value: AutoBackupSettings) { calls += "automatic"; values.value = values.value.copy(automatic = value) }
        override suspend fun path(value: String?) { calls += "path"; operationGate?.let { withContext(NonCancellable) { it.await(); error("late failure") } }; values.value = values.value.copy(backupPath = value) }
        override suspend fun localPassword(value: String) { calls += "local-password" }
        override suspend fun needsHelp() = false
    }
    private class Choices : BackupChoicesRepository {
        var rows = listOf(BackupChoice("books", "Books", true)); var saves = 0
        override suspend fun load(group: BackupChoiceGroup) = rows
        override suspend fun toggle(group: BackupChoiceGroup, key: String, checked: Boolean): List<BackupChoice> { rows = rows.map { if (it.key == key) it.copy(checked = checked) else it }; return rows }
        override suspend fun save() { saves++ }
    }
}
