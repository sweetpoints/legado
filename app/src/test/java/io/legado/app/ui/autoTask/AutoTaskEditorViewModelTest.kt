package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTaskEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AutoTaskEditorViewModel>()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { models.forEach { it.stop(); it.viewModelScope.cancel() }; dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun model(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle(), id: String? = null) = AutoTaskEditorViewModel(repo, saved, id).also { models += it }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun valid(vm: AutoTaskEditorViewModel) {
        vm.field(AutoTaskEditorField.Name, AutoTaskEditorText("Task")); vm.field(AutoTaskEditorField.Script, AutoTaskEditorText("42"))
    }
    @Test fun newDefaultsAndExistingLoadUseAllFieldsWithoutExposingMutableEntity() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo, id = "old"); runCurrent()
        AutoTaskEditorField.entries.forEach { assertEquals(repo.loaded!![it], vm.state.value.draft[it]) }
        assertFalse(vm.state.value.draft.enabled); assertFalse(vm.state.value.draft.cookieJar)
        val fresh = model(); runCurrent(); assertEquals("*/30 * * * *", fresh.state.value.draft[AutoTaskEditorField.Cron].text)
        assertTrue(fresh.state.value.draft.enabled); assertTrue(fresh.state.value.draft.cookieJar)
    }
    @Test fun missingExistingClosesWithoutSaveAndFailedLoadingCanRetrySameId() = runTest(dispatcher) {
        val repo = Fake(); repo.loaded = null; val vm = model(repo, id = "old"); runCurrent()
        assertTrue(vm.state.value.missing); assertEquals(AutoTaskEditorEffectKind.Close, vm.state.value.effects.single().kind); assertEquals(0, repo.saves)
        val failed = Fake(); failed.failLoad = true; val retry = model(failed, id = "old"); runCurrent(); assertNotNull(retry.state.value.error)
        failed.failLoad = false; retry.retry(); runCurrent(); assertEquals(listOf("old", "old"), failed.loadedIds)
    }
    @Test fun nameCronAndNormalizedScriptValidationRejectsEachInvalidCaseBeforeRepository() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent()
        vm.save(AutoTaskEditorSaveAction.Close); assertEquals(AutoTaskEditorIssue.Name, vm.state.value.issue)
        vm.field(AutoTaskEditorField.Name, AutoTaskEditorText("name")); vm.field(AutoTaskEditorField.Cron, AutoTaskEditorText("bad"))
        vm.save(AutoTaskEditorSaveAction.Close); assertEquals(AutoTaskEditorIssue.Cron, vm.state.value.issue)
        vm.field(AutoTaskEditorField.Cron, AutoTaskEditorText("0 * * * *")); vm.field(AutoTaskEditorField.Script, AutoTaskEditorText("  <js> </js>  "))
        vm.save(AutoTaskEditorSaveAction.Close); assertEquals(AutoTaskEditorIssue.Script, vm.state.value.issue); assertEquals(0, repo.saves)
    }
    @Test fun largeDraftAndEveryCursorRestoreFromDiskAndNeverEnterSavedStateBundle() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent()
        val text = "large".repeat(200000)
        AutoTaskEditorField.entries.forEach { vm.field(it, AutoTaskEditorText(if (it == AutoTaskEditorField.Script) text else it.name, 3, 4)) }
        vm.enabled(false); vm.cookieJar(false); vm.focus(AutoTaskEditorField.LoginUrl); runCurrent(); vm.flush()
        val restored = model(repo, snapshot(saved)); runCurrent(); assertEquals(vm.state.value.draft, restored.state.value.draft)
        assertEquals(AutoTaskEditorField.LoginUrl, restored.state.value.focus)
        assertTrue(saved.keys().all { (saved.get<Any?>(it) as? String)?.length?.let { it < 1000 } != false })
    }
    @Test fun lateLoadBlocksTypingAndCannotOverwriteRestoredDraft() = runTest(dispatcher) {
        val repo = Fake(); repo.loadGate = CompletableDeferred(); val vm = model(repo, id = "old"); runCurrent()
        vm.field(AutoTaskEditorField.Name, AutoTaskEditorText("early")); assertEquals("", vm.state.value.draft[AutoTaskEditorField.Name].text)
        repo.loadGate!!.complete(Unit); runCurrent(); assertEquals("Loaded", vm.state.value.draft[AutoTaskEditorField.Name].text)
    }
    @Test fun cursorOnlyAndTrimEquivalentEditsDoNotPromptButUnsavedPasteDoes() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo, id = "old"); runCurrent()
        vm.field(AutoTaskEditorField.Name, AutoTaskEditorText("  Loaded  ", 3)); vm.requestExit(); assertFalse(vm.state.value.exit)
        vm.consume(vm.state.value.effects.single()); val other = model(repo, id = "old"); runCurrent()
        other.field(AutoTaskEditorField.Script, AutoTaskEditorText("changed")); other.requestExit(); assertTrue(other.state.value.exit)
        other.keepEditing(); assertFalse(other.state.value.exit); other.requestExit(); other.discard(); assertEquals(AutoTaskEditorEffectKind.Close, other.state.value.effects.single().kind)
        assertEquals(0, repo.saves)
    }
    @Test fun saveFailureRetainsDraftAndSuccessQueuesAfterSaveOnlyAndUpdatesBaseline() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); valid(vm); repo.failSave = true
        vm.save(AutoTaskEditorSaveAction.Debug); runCurrent(); assertNotNull(vm.state.value.error); assertTrue(vm.state.value.effects.isEmpty())
        assertEquals("42", vm.state.value.draft[AutoTaskEditorField.Script].text)
        repo.failSave = false; vm.save(AutoTaskEditorSaveAction.Debug); runCurrent()
        assertEquals(AutoTaskEditorEffectKind.SavedDebug, vm.state.value.effects.single().kind); assertTrue(vm.state.value.savedResult)
        vm.consume(vm.state.value.effects.single()); vm.requestExit(); assertFalse(vm.state.value.exit)
    }
    @Test fun consumedSuccessfulActionDoesNotRepeatAndDurablePendingActionRecoversAfterCancellation() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent(); valid(vm); runCurrent()
        repo.saveGate = CompletableDeferred(); vm.save(AutoTaskEditorSaveAction.Debug); runCurrent()
        val before = snapshot(saved); vm.stop(); repo.saveGate!!.complete(Unit); runCurrent()
        assertTrue(vm.state.value.effects.isEmpty()); val restored = model(repo, before); runCurrent()
        val effect = restored.state.value.effects.single(); assertEquals(AutoTaskEditorEffectKind.SavedDebug, effect.kind)
        restored.consume(effect); val again = model(repo, snapshot(before)); runCurrent(); assertTrue(again.state.value.effects.isEmpty()); assertEquals(1, repo.saves)
    }
    @Test fun loginWithoutCapabilityStillSavesButDoesNotNavigate() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); valid(vm); vm.save(AutoTaskEditorSaveAction.Login); runCurrent()
        assertEquals(1, repo.saves); assertEquals(AutoTaskEditorIssue.NoLogin, vm.state.value.issue)
        assertEquals(AutoTaskEditorEffectKind.SavedOnly, vm.state.value.effects.single().kind)
    }
    @Test fun objectAndSingleArrayPasteAllEditableMetadataKeepOriginalIdAndDoNotSave() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo, id = "old"); runCurrent()
        val imported = AutoTaskRule("foreign", "New", false, "0 * * * *", loginUrl = "login", loginUi = "form", loginCheckJs = "check", comment = "comment", script = "script", header = "header", jsLib = "lib", concurrentRate = "4", enabledCookieJar = false, customOrder = 99, lastLog = "private")
        vm.paste(GSON.toJson(imported)); runCurrent(); assertEquals(AutoTaskEditorDraft.from(imported), vm.state.value.draft); assertEquals("old", vm.taskId)
        vm.paste(GSON.toJson(listOf(imported))); runCurrent(); assertEquals("lib", vm.state.value.draft[AutoTaskEditorField.JsLib].text)
        vm.paste(GSON.toJson(listOf(imported, imported))); runCurrent(); assertEquals(AutoTaskEditorIssue.Format, vm.state.value.issue); assertEquals(0, repo.saves)
    }
    @Test fun copyUsesOriginalIdAndAllEditableValuesWithoutSavingOrRuntimeMetadata() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo, id = "old"); runCurrent(); vm.copy()
        val copied = GSON.fromJsonArray<AutoTaskRule>(vm.copyText()).getOrThrow().single()
        assertEquals("old", copied.id); assertEquals("loaded script", copied.script); assertEquals("lib", copied.jsLib); assertEquals(0, repo.saves)
        assertEquals(AutoTaskEditorEffectKind.Clipboard, vm.state.value.effects.single().kind)
    }
    @Test fun codeFieldGateCursorRoundTripDuplicateReturnAndCancelPreserveExactDraft() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.focus(AutoTaskEditorField.Name); vm.openEditor(); assertEquals(AutoTaskEditorIssue.Focus, vm.state.value.issue)
        vm.field(AutoTaskEditorField.LoginUrl, AutoTaskEditorText("line1\nline2", 4, 5)); vm.focus(AutoTaskEditorField.LoginUrl); vm.openEditor(); runCurrent()
        val effect = vm.state.value.effects.single(); assertEquals(AutoTaskEditorField.LoginUrl, effect.field); assertEquals(4, effect.cursor)
        vm.consume(effect); vm.editorReturned(true, "returned", null, 99); runCurrent()
        assertEquals(AutoTaskEditorText("returned", 8), vm.state.value.draft[AutoTaskEditorField.LoginUrl]); assertFalse(vm.state.value.editorPending)
        vm.editorReturned(true, "duplicate", null, 0); runCurrent(); assertEquals("returned", vm.state.value.draft[AutoTaskEditorField.LoginUrl].text)
        vm.openEditor(); runCurrent(); vm.consume(vm.state.value.effects.single()); vm.editorReturned(false, "ignored", null, 0); runCurrent()
        assertEquals("returned", vm.state.value.draft[AutoTaskEditorField.LoginUrl].text)
    }
    @Test fun canceledReturnedFileTransactionPersistsBeforeCleanupAndSnapshotRestoresWithoutLateUi() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); runCurrent()
        vm.focus(AutoTaskEditorField.Script); vm.openEditor(); runCurrent(); vm.consume(vm.state.value.effects.single())
        repo.files["output"] = "Returned body"; repo.readGate = CompletableDeferred()
        vm.editorReturned(true, null, "output", 5); runCurrent(); val before = snapshot(saved); vm.stop()
        assertTrue(repo.files.containsKey("output")); repo.readGate!!.complete(Unit); runCurrent()
        assertFalse(repo.files.containsKey("output")); assertEquals("", vm.state.value.draft[AutoTaskEditorField.Script].text)
        val restored = model(repo, before); runCurrent(); assertEquals(AutoTaskEditorText("Returned body", 5), restored.state.value.draft[AutoTaskEditorField.Script]); assertFalse(restored.state.value.editorPending)
    }
    @Test fun unreadableEditorResultKeepsFilesAndDraftUntilExplicitRetryOrDiscard() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); runCurrent(); vm.focus(AutoTaskEditorField.Script); vm.openEditor(); runCurrent(); vm.consume(vm.state.value.effects.single())
        repo.failRead = true; repo.files["output"] = "body"; vm.editorReturned(true, null, "output", 2); runCurrent()
        assertTrue(vm.state.value.editorPending); assertNotNull(vm.state.value.error); assertTrue(repo.files.containsKey("output"))
        repo.failRead = false; vm.retryEditorResult(); runCurrent(); assertEquals("body", vm.state.value.draft[AutoTaskEditorField.Script].text)
        vm.openEditor(); runCurrent(); vm.consume(vm.state.value.effects.single()); repo.failRead = true; repo.files["output"] = "another"; vm.editorReturned(true, null, "output", 2); runCurrent()
        vm.discardEditorResult(); runCurrent(); assertFalse(vm.state.value.editorPending); assertEquals("body", vm.state.value.draft[AutoTaskEditorField.Script].text)
    }
    @Test fun stoppedEditorRejectsNoncooperativeLoadFailureWithoutPublishingState() = runTest(dispatcher) {
        val repo = Fake().apply { loadGate = CompletableDeferred(); uncooperativeLoad = true; failLoad = true }
        val vm = model(repo, id = "old"); runCurrent(); val before = vm.state.value
        vm.stop(); repo.loadGate!!.complete(Unit); runCurrent()
        assertEquals(before, vm.state.value); assertTrue(vm.state.value.effects.isEmpty())
    }
    private class Fake : AutoTaskEditorRepository {
        var loaded: AutoTaskEditorDraft? = AutoTaskEditorDraft.from(AutoTaskRule("old", "Loaded", false, "0 * * * *", script = "loaded script", header = "header", jsLib = "lib", enabledCookieJar = false))
        val loadedIds = mutableListOf<String>(); val drafts = mutableMapOf<String, AutoTaskEditorDocument>(); val files = mutableMapOf<String, String>()
        var uncooperativeLoad = false; var saves = 0; var failLoad = false; var failSave = false; var failRead = false
        var loadGate: CompletableDeferred<Unit>? = null; var saveGate: CompletableDeferred<Unit>? = null; var readGate: CompletableDeferred<Unit>? = null
        override suspend fun load(id: String): AutoTaskEditorDraft? { loadedIds += id; if (uncooperativeLoad) withContext(NonCancellable) { loadGate?.await() } else loadGate?.await(); if (failLoad) error("load failed"); return loaded }
        override suspend fun readDraft(session: String) = drafts[session]
        override suspend fun writeDraft(session: String, document: AutoTaskEditorDocument) { if (document.revision >= (drafts[session]?.revision ?: -1)) drafts[session] = document }
        override suspend fun save(session: String, document: AutoTaskEditorDocument, action: AutoTaskEditorSaveAction): AutoTaskEditorDocument = withContext(NonCancellable) {
            saveGate?.await(); if (failSave) error("save failed"); saves++
            document.copy(existing = true, baseline = document.draft, revision = document.revision + 1,
                delivery = AutoTaskEditorDelivery("save-$saves", action, document.draft[AutoTaskEditorField.LoginUrl].text.isNotEmpty())).also { writeDraft(session, it) }
        }
        override suspend fun parse(text: String) = (GSON.fromJsonObject<AutoTaskRule>(text).getOrNull() ?: GSON.fromJsonArray<AutoTaskRule>(text).getOrNull()?.singleOrNull())?.let(AutoTaskEditorDraft::from)
        override suspend fun export(id: String, draft: AutoTaskEditorDraft) = GSON.toJson(listOf(draft.entity(id)))
        override suspend fun editorInput(text: String) = "input".also { files[it] = text }
        override suspend fun editorText(path: String): String { readGate?.await(); if (failRead) error("read failed"); return files[path] ?: error("missing output") }
        override suspend fun clearEditor(vararg paths: String?) { paths.forEach { files.remove(it) } }
    }
}
