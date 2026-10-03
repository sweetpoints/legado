package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DirectLinkConfigViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<DirectLinkConfigViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop() }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private val valid = DirectLinkDraft("https://upload", "$.url", "Fixture", false, "0")
    private inner class Fake : DirectLinkConfigRepository {
        val id = UUID.randomUUID().toString(); var session = DirectLinkSession(id, valid)
        var writes = 0; var failWrite = false; var failSave = false; var failTest = false; var tests = 0; var released = false
        var gate: CompletableDeferred<Unit>? = null; val saves = mutableListOf<DirectLinkDraft>()
        override suspend fun open(id: String?) = session
        override suspend fun defaults() = listOf(valid.copy(summary = "Default"))
        override suspend fun write(value: DirectLinkSession) { if (failWrite) error("Disk failed"); check(!released); if (value.revision > session.revision) { session = value; writes++ } }
        override suspend fun save(draft: DirectLinkDraft) { withContext(NonCancellable) { gate?.await() }; if (failSave) error("Save failed"); saves += draft }
        override suspend fun test(draft: DirectLinkDraft): String { tests++; withContext(NonCancellable) { gate?.await() }; if (failTest) error("Upload failed"); return "Result" }
        override suspend fun release(id: String) { released = true }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = DirectLinkConfigViewModel(repo, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun validationPreservesOrderedRequiredFieldsAndExactExpiryBoundaries() {
        assertEquals(DirectLinkIssue.Upload, DirectLinkDraft().issue())
        assertEquals(DirectLinkIssue.Download, DirectLinkDraft(uploadUrl = "url").issue())
        assertEquals(DirectLinkIssue.Summary, DirectLinkDraft(uploadUrl = "url", downloadRule = "rule").issue())
        assertEquals(DirectLinkIssue.Expiry, valid.copy(expiry = "36501").issue())
        assertEquals(DirectLinkIssue.Expiry, valid.copy(expiry = "99999999999999999999").issue())
        assertNull(valid.copy(expiry = "36500").issue()); assertNull(valid.copy(expiry = "0").issue())
    }
    @Test fun draftRestoresCompleteLargeScriptsWithOnlyUuidInSavedStateAndNeverSavesConfigOnEdit() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent()
        first.edit { it.copy(downloadRule = "x".repeat(400000), expiry = "36500") }; runCurrent()
        assertTrue(repo.saves.isEmpty()); assertEquals(setOf("directLink.session"), saved.keys()); assertTrue(saved.get<String>("directLink.session")!!.length < 64)
        first.stop(); val restored = model(repo, copy(saved)); runCurrent(); assertEquals(400000, restored.state.value.session!!.draft.downloadRule.length)
        assertEquals("36500", restored.state.value.session!!.draft.expiry)
    }
    @Test fun presetsAndClipboardPreserveAllFieldsAndInvalidClipboardKeepsDraft() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.preset(0); runCurrent(); assertEquals("Default", vm.state.value.session!!.draft.summary)
        vm.paste("{}"); assertEquals(DirectLinkIssue.Clipboard, vm.state.value.issue); assertEquals("Default", vm.state.value.session!!.draft.summary)
        vm.paste("{\"uploadUrl\":\"post\",\"downloadUrlRule\":\"rule\",\"summary\":\"Imported\",\"compress\":true,\"expiryDate\":31}"); runCurrent()
        assertEquals(DirectLinkDraft("post", "rule", "Imported", true, "31"), vm.state.value.session!!.draft)
        assertTrue(vm.copy()!!.contains("Imported")); assertTrue(repo.saves.isEmpty())
    }
    @Test fun failedDraftWriteBlocksSavingUntilLatestDraftRetrySucceeds() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); repo.failWrite = true; vm.edit { it.copy(summary = "New") }; runCurrent()
        vm.save(); runCurrent(); assertTrue(repo.saves.isEmpty()); assertNotNull(vm.state.value.error)
        repo.failWrite = false; vm.retryDraft(); runCurrent(); vm.save(); runCurrent()
        assertEquals("New", repo.saves.single().summary); assertTrue(vm.state.value.finished)
    }
    @Test fun duplicateSaveIsIgnoredAndFailedConfigCanBeCorrectedAndRetried() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); repo.failSave = true; vm.save(); vm.save(); runCurrent()
        assertFalse(vm.state.value.saving); assertFalse(vm.state.value.finished); assertTrue(repo.saves.isEmpty())
        repo.failSave = false; vm.edit { it.copy(summary = "Retry") }; runCurrent(); vm.save(); vm.save(); runCurrent()
        assertEquals(1, repo.saves.size); assertTrue(repo.session.finished)
    }
    @Test fun testIsExplicitAndFailureResultRestoresWithoutReuploadingAndCanBeDismissed() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent(); assertEquals(0, repo.tests)
        repo.failTest = true; vm.test(); vm.test(); runCurrent(); assertEquals(1, repo.tests); assertEquals("Upload failed", vm.state.value.session!!.result)
        vm.stop(); val restored = model(repo, copy(saved)); runCurrent(); assertEquals("Upload failed", restored.state.value.session!!.result); assertEquals(1, repo.tests)
        restored.clearResult(); runCurrent(); assertNull(repo.session.result); assertTrue(repo.saves.isEmpty())
    }
    @Test fun canceledNonCooperativeUploadCannotPublishOrOverwriteLaterDraft() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); repo.gate = CompletableDeferred(); vm.test(); runCurrent(); vm.cancelTest()
        vm.edit { it.copy(summary = "Later") }; runCurrent(); repo.gate!!.complete(Unit); runCurrent()
        assertNull(vm.state.value.session!!.result); assertEquals("Later", repo.session.draft.summary); assertFalse(vm.state.value.testing)
    }
    @Test fun saveBlocksCloseAndCancelReleasesOnlyOwnedDraftWithoutChangingConfig() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); repo.gate = CompletableDeferred(); vm.save(); runCurrent(); vm.close()
        assertFalse(vm.state.value.finished); assertFalse(repo.released); repo.gate!!.complete(Unit); runCurrent(); assertTrue(vm.state.value.finished)
        val other = Fake(); val cancel = model(other); runCurrent(); cancel.close(); runCurrent(); assertTrue(other.released); assertTrue(other.saves.isEmpty())
    }
}
