package io.legado.app.ui.replace.edit

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReplaceEditorViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private class Fake : ReplaceEditorRepository {
        var initial = ReplaceEditorDraft(id = 42, enabled = false, order = 7)
        val documents = mutableMapOf<String, ReplaceEditorDocument>()
        var loadGate: CompletableDeferred<Unit>? = null; var previewGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null; var pasteGate: CompletableDeferred<Unit>? = null
        var nonCooperative = false; var fail = false; var writeFail = false; var readFail = false; var saves = 0
        var editorGate: CompletableDeferred<Unit>? = null; val inputTexts = mutableMapOf<String, String>()
        val previews = mutableListOf<ReplaceEditorDraft>()
        val deleted = mutableListOf<String>()
        override suspend fun load(request: ReplaceEditorRequest): ReplaceEditorDraft {
            if (nonCooperative) withContext(NonCancellable) { loadGate?.await() } else loadGate?.await()
            if (fail) error("failed"); return initial
        }
        override suspend fun read(session: String) = documents[session]
        override suspend fun write(session: String, document: ReplaceEditorDocument) {
            if (writeFail) error("write failed")
            val current = documents[session]
            if (current?.receipt == null && (current == null || current.revision <= document.revision)) documents[session] = document
        }
        override suspend fun save(session: String, document: ReplaceEditorDocument): ReplaceEditorDocument {
            saves++; if (nonCooperative) withContext(NonCancellable) { saveGate?.await() } else saveGate?.await()
            if (fail) error("invalid"); return document.copy(receipt = "receipt", revision = document.revision + 1).also { documents[session] = it }
        }
        override suspend fun parse(text: String, sampleId: Long): ReplaceEditorDraft { pasteGate?.await(); return initial.with(ReplaceEditorField.Name, ReplaceEditorText(text)).copy(id = 999, enabled = true, order = 99) }
        override suspend fun export(draft: ReplaceEditorDraft) = draft[ReplaceEditorField.Sample].text
        override suspend fun preview(draft: ReplaceEditorDraft): String { previews += draft; if (nonCooperative) withContext(NonCancellable) { previewGate?.await() } else previewGate?.await(); return draft[ReplaceEditorField.Pattern].text + draft[ReplaceEditorField.Sample].text }
        override suspend fun editorInput(text: String): String { val path = if (inputTexts.isEmpty()) "input-path" else "returned-inline-path"; inputTexts[path] = text; return path }
        override suspend fun editorText(path: String): String {
            if (nonCooperative) withContext(NonCancellable) { editorGate?.await() } else editorGate?.await()
            if (readFail) error("read failed")
            return inputTexts[path] ?: "returned text"
        }
        override suspend fun clearEditor(vararg paths: String?) { deleted += paths.filterNotNull() }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = ReplaceEditorViewModel(repo, saved, ReplaceEditorRequest()).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun pendingAndFailedLoadDisablesInteractionAndCanRetry() = test {
        val repo = Fake().apply { loadGate = CompletableDeferred(); fail = true }; val model = model(repo); runCurrent()
        model.field(ReplaceEditorField.Name, ReplaceEditorText("ignored")); model.save(); assertEquals(0, repo.saves)
        repo.loadGate!!.complete(Unit); runCurrent(); assertFalse(model.state.value.loaded)
        repo.fail = false; model.load(); runCurrent(); assertTrue(model.state.value.editable)
    }
    @Test fun diskDraftRestoresLargeFieldsSelectionAndOnlySmallSavedState() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent()
        val large = "text".repeat(30000); first.field(ReplaceEditorField.Pattern, ReplaceEditorText(large, 21, 4)); first.focus(ReplaceEditorField.Pattern); first.scroll(1234); runCurrent()
        val restored = model(repo, copy(saved)); runCurrent()
        assertEquals(large, restored.state.value.draft[ReplaceEditorField.Pattern].text); assertEquals(21, restored.state.value.draft[ReplaceEditorField.Pattern].start)
        assertEquals(4, restored.state.value.draft[ReplaceEditorField.Pattern].end); assertEquals(1234, restored.state.value.scroll)
        assertTrue(saved.keys().mapNotNull { saved.get<Any?>(it) }.filterIsInstance<String>().all { it.length < 100 })
    }
    @Test fun selectionMovementIsCleanButAllFlagsAndSampleAreDirtyAndYesKeepsEditing() = test {
        val repo = Fake(); val clean = model(repo); runCurrent(); clean.field(ReplaceEditorField.Name, ReplaceEditorText("", 0)); clean.close(); assertTrue(clean.state.value.finished)
        val model = model(Fake()); runCurrent(); model.flags(source = true); model.close(); assertTrue(model.state.value.exit); model.keepEditing(); assertFalse(model.state.value.finished)
        model.field(ReplaceEditorField.Sample, ReplaceEditorText("sample")); model.close(); model.discard(); assertTrue(model.state.value.finished); assertEquals(0, repo.saves)
    }
    @Test fun sampleTruncatesSurrogatesAndInsertionUndoRedoRetainSelectedRange() = test {
        val model = model(Fake()); runCurrent(); model.field(ReplaceEditorField.Sample, ReplaceEditorText("x".repeat(299) + "😀", 301))
        assertEquals(299, model.state.value.draft[ReplaceEditorField.Sample].text.length); assertTrue(model.state.value.truncated)
        model.field(ReplaceEditorField.Pattern, ReplaceEditorText("abcdef", 4, 2)); model.focus(ReplaceEditorField.Pattern); model.insert("X")
        assertEquals("abXef", model.state.value.draft[ReplaceEditorField.Pattern].text); assertEquals(3, model.state.value.draft[ReplaceEditorField.Pattern].start)
        model.undo(); assertEquals("abcdef", model.state.value.draft[ReplaceEditorField.Pattern].text); model.redo(); assertEquals("abXef", model.state.value.draft[ReplaceEditorField.Pattern].text)
    }
    @Test fun pastePreservesCurrentHiddenMetadataAndLatePasteCannotOverwriteNewEdits() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.paste("paste"); runCurrent()
        assertEquals(42L, model.state.value.draft.id); assertFalse(model.state.value.draft.enabled); assertEquals(7, model.state.value.draft.order)
        repo.pasteGate = CompletableDeferred(); model.paste("late"); runCurrent(); model.field(ReplaceEditorField.Name, ReplaceEditorText("new")); repo.pasteGate!!.complete(Unit); runCurrent()
        assertEquals("new", model.state.value.draft[ReplaceEditorField.Name].text)
    }
    @Test fun debounceOnlyPublishesLatestPreviewEvenWhenOldWorkIgnoresCancellation() = test {
        val repo = Fake().apply { nonCooperative = true; previewGate = CompletableDeferred() }; val model = model(repo); runCurrent()
        model.field(ReplaceEditorField.Pattern, ReplaceEditorText("old")); advanceTimeBy(250); runCurrent()
        model.field(ReplaceEditorField.Pattern, ReplaceEditorText("new")); repo.previewGate!!.complete(Unit); runCurrent()
        assertNotEquals("old", model.state.value.preview); advanceTimeBy(250); runCurrent(); assertEquals("new", model.state.value.preview)
    }
    @Test fun saveWaitsForReceiptBlocksDuplicatesAndRestoredReceiptNeverSavesAgain() = test {
        val repo = Fake().apply { saveGate = CompletableDeferred() }; val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent()
        model.save(); model.save(); model.close(); model.field(ReplaceEditorField.Name, ReplaceEditorText("ignored")); runCurrent()
        assertEquals(1, repo.saves); assertFalse(model.state.value.finished)
        repo.saveGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.saved); assertTrue(model.state.value.finished)
        val restoredSaved = copy(saved).apply { remove<Boolean>("replaceEditor.finished"); remove<Boolean>("replaceEditor.saved") }
        val restored = model(repo, restoredSaved); runCurrent(); restored.save(); assertTrue(restored.state.value.finished); assertTrue(restored.state.value.saved); assertEquals(1, repo.saves)
    }
    @Test fun failedSaveLeavesDraftEditableAndRetryCanSucceed() = test {
        val repo = Fake().apply { fail = false }; val model = model(repo); runCurrent(); repo.fail = true; model.save(); runCurrent()
        assertFalse(model.state.value.finished); assertTrue(model.state.value.editable); assertTrue(model.state.value.error!!.startsWith("save error"))
        repo.fail = false; model.save(); runCurrent(); assertTrue(model.state.value.saved)
    }
    @Test fun cursorOnlyDiscardResultRestoresSelectionWithoutChangingRawContentOrDirtyStatus() = test {
        val repo = Fake().apply { initial = initial.with(ReplaceEditorField.Pattern, ReplaceEditorText("abc")) }
        val model = model(repo); runCurrent(); model.openEditor(ReplaceEditorField.Pattern); runCurrent(); val launch = model.state.value.editor!!
        model.editorLaunched(launch.nonce); model.editorResult(launch.nonce, null, null, 99, true); runCurrent()
        assertEquals("abc", model.state.value.draft[ReplaceEditorField.Pattern].text); assertEquals(3, model.state.value.draft[ReplaceEditorField.Pattern].start)
        model.editorResult(launch.nonce, "wrong", null, 0, true); runCurrent(); assertEquals("abc", model.state.value.draft[ReplaceEditorField.Pattern].text)
        model.close(); assertTrue(model.state.value.finished); assertFalse(model.state.value.exit)
    }
    @Test fun editorPendingRestoresSmallTicketAndReturnedFileUpdatesExactTargetOnlyOnce() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent(); first.openEditor(ReplaceEditorField.Replacement); runCurrent()
        val launch = first.state.value.editor!!; first.editorLaunched(launch.nonce)
        val restored = model(repo, copy(saved)); runCurrent(); assertFalse(restored.state.value.editorLaunch)
        restored.editorResult(launch.nonce, null, "output-path", 4, true); runCurrent()
        assertEquals("returned text", restored.state.value.draft[ReplaceEditorField.Replacement].text); assertEquals(4, restored.state.value.draft[ReplaceEditorField.Replacement].start)
        assertEquals("", restored.state.value.draft[ReplaceEditorField.Pattern].text); assertNull(restored.state.value.editor); assertEquals(listOf("input-path", "output-path"), repo.deleted)
    }
    @Test fun nonCooperativeLoadAndSaveCannotPublishAfterStop() = test {
        val loadingRepo = Fake().apply { nonCooperative = true; loadGate = CompletableDeferred() }; val loading = model(loadingRepo); runCurrent(); loading.stop(); loadingRepo.loadGate!!.complete(Unit); runCurrent(); assertFalse(loading.state.value.loaded)
        val savingRepo = Fake().apply { nonCooperative = true; saveGate = CompletableDeferred() }; val saving = model(savingRepo); runCurrent(); saving.save(); runCurrent(); saving.stop(); savingRepo.saveGate!!.complete(Unit); runCurrent(); assertFalse(saving.state.value.finished)
    }
    @Test fun undoRedoOnlyTouchesFocusedFieldAndNeverOtherFieldsOrFlags() = test {
        val model = model(Fake()); runCurrent()
        model.field(ReplaceEditorField.Name, ReplaceEditorText("Name")); model.field(ReplaceEditorField.Pattern, ReplaceEditorText("Pattern")); model.flags(source = true)
        model.focus(ReplaceEditorField.Name); model.undo()
        assertEquals("", model.state.value.draft[ReplaceEditorField.Name].text)
        assertEquals("Pattern", model.state.value.draft[ReplaceEditorField.Pattern].text); assertTrue(model.state.value.draft.source)
        model.redo(); assertEquals("Name", model.state.value.draft[ReplaceEditorField.Name].text)
        model.focus(ReplaceEditorField.Pattern); model.undo(); assertEquals("", model.state.value.draft[ReplaceEditorField.Pattern].text)
        assertEquals("Name", model.state.value.draft[ReplaceEditorField.Name].text)
    }

    @Test fun canceledNonCooperativeEditorReadRestoresTicketAndAppliesExactlyOnceAfterResume() = test {
        val repo = Fake().apply { nonCooperative = true; editorGate = CompletableDeferred() }; val saved = SavedStateHandle()
        val first = model(repo, saved); runCurrent(); first.openEditor(ReplaceEditorField.Pattern); runCurrent()
        val nonce = first.state.value.editor!!.nonce; first.editorLaunched(nonce); first.editorResult(nonce, null, "output-path", 4, true); runCurrent()
        first.stop(); repo.editorGate!!.complete(Unit); runCurrent()
        assertEquals("", first.state.value.draft[ReplaceEditorField.Pattern].text); assertTrue(repo.deleted.isEmpty())
        val restored = model(repo, copy(saved)); runCurrent()
        assertEquals("returned text", restored.state.value.draft[ReplaceEditorField.Pattern].text); assertNull(restored.state.value.editor)
        restored.editorResult(nonce, "duplicate", null, 0, true); runCurrent(); assertEquals("returned text", restored.state.value.draft[ReplaceEditorField.Pattern].text)
    }
    @Test fun editorReadAndDiskFailuresKeepPathForRetryAndLargeInlineNeverEntersSavedState() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent()
        model.openEditor(ReplaceEditorField.Replacement); runCurrent(); val nonce = model.state.value.editor!!.nonce
        repo.readFail = true; val large = "large".repeat(400000); model.editorResult(nonce, large, null, 7, true); runCurrent()
        assertTrue(model.state.value.editorReturning); assertTrue(repo.deleted.isEmpty())
        assertTrue(saved.keys().mapNotNull { saved.get<Any?>(it) }.filterIsInstance<String>().all { it.length < 100 })
        repo.readFail = false; repo.writeFail = true; model.retryEditor(); runCurrent(); assertTrue(model.state.value.editorReturning); assertTrue(repo.deleted.isEmpty())
        repo.writeFail = false; model.retryEditor(); runCurrent(); assertNull(model.state.value.editor)
        assertEquals(large, repo.documents[model.session]!!.draft[ReplaceEditorField.Replacement].text)
        assertEquals(7, model.state.value.draft[ReplaceEditorField.Replacement].start)
        assertEquals(listOf("input-path", "returned-inline-path"), repo.deleted)
    }

}
