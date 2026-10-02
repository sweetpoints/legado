package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodeDialogViewModelTest {
    private val models = mutableListOf<CodeDialogViewModel>()
    @After fun cleanup() { models.forEach { it.stop() }; Dispatchers.resetMain() }
    private fun scenario(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { models.forEach { it.stop() }; runCurrent() }
    }
    private class Store : CodeDialogRepository {
        val disk = mutableMapOf<String, CodeDialogDraft>(); var readGate: CompletableDeferred<Unit>? = null
        var failRead = false; var failWrite = false
        override suspend fun read(id: String): CodeDialogDraft? { readGate?.await(); if (failRead) error("read failure"); return disk[id] }
        override suspend fun write(id: String, draft: CodeDialogDraft) { if (failWrite) error("disk full"); if ((disk[id]?.revision ?: -1) <= draft.revision) disk[id] = draft }
    }
    private fun vm(store: Store = Store(), saved: SavedStateHandle = SavedStateHandle(), editable: Boolean = true,
        source: Boolean = false, original: String = "abc ABC abc", alternate: String? = "derived", show: Boolean = false) =
        CodeDialogViewModel(store, saved, original, alternate, editable, source, show, Dispatchers.Main).also { models += it }
    @Test fun delayedRestoreDisablesEditingAndReusesDiskDraft() = scenario {
        val store = Store(); val saved = SavedStateHandle(); val model = vm(store, saved)
        runCurrent(); model.text("latest", 6, 6); runCurrent(); model.stop()
        store.readGate = CompletableDeferred()
        val restored = vm(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), original = "old")
        runCurrent(); restored.text("early", 0, 0); assertFalse(restored.state.value.loaded)
        store.readGate!!.complete(Unit); runCurrent(); assertEquals("latest", restored.state.value.original)
        assertEquals(6, restored.state.value.selectionStart)
    }
    @Test fun largeDraftAndAlternateNeverEnterSavedState() = scenario {
        val store = Store(); val saved = SavedStateHandle(); val model = vm(store, saved); runCurrent()
        val text = "a".repeat(500_000); model.text(text, text.length, 3); runCurrent(); model.flushDraft()
        assertEquals(text, store.disk.values.single().original)
        assertEquals(text.length, model.state.value.selectionStart); assertEquals(3, model.state.value.selectionEnd)
        assertTrue(saved.keys().all { ((saved.get<Any>(it) as? String)?.length ?: 0) < 1000 })
    }
    @Test fun alternatePreviewNeverOverwritesOriginalAndCannotBeEdited() = scenario {
        val model = vm(source = true); runCurrent(); model.text("edited", 6, 6); model.preview(true)
        assertEquals("derived", model.state.value.displayed); model.text("bad", 0, 0)
        assertEquals("edited", model.state.value.original); model.preview(false)
        assertEquals("edited", model.state.value.displayed)
    }
    @Test fun removedAlternateSurvivesRestoreWithoutResurrectingInitialPreview() = scenario {
        val store = Store(); val saved = SavedStateHandle(); val model = vm(store, saved, show = true); runCurrent()
        model.alternate(null); runCurrent(); model.stop()
        val restored = vm(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), show = true)
        runCurrent(); assertNull(restored.state.value.alternate); assertFalse(restored.state.value.showingAlternate)
    }
    @Test fun literalSearchWrapsCaseInsensitiveAndTracksSelection() = scenario {
        val model = vm(); runCurrent(); model.searchOpen(true); model.search("abc"); runCurrent()
        assertEquals(3, model.state.value.matches.size); assertEquals(0, model.state.value.matchIndex)
        model.match(-1); assertEquals(2, model.state.value.matchIndex); assertEquals(8, model.state.value.selectionStart)
        model.match(3); assertEquals(0, model.state.value.matchIndex)
        model.search("["); runCurrent(); assertEquals(-1, model.state.value.matchIndex); assertTrue(model.state.value.matches.isEmpty())
        model.searchOpen(false); assertTrue(model.state.value.matches.isEmpty())
    }
    @Test fun refreshPendingBlocksEditsActionsAndCloseWithoutLosingOriginal() = scenario {
        val model = vm(source = true); runCurrent(); model.refreshPending(true)
        model.text("bad", 0, 0); model.preview(true); model.action(CodeDialogAction.Save); model.close()
        assertEquals("abc ABC abc", model.state.value.original); assertFalse(model.state.value.finished)
        assertTrue(model.state.value.effects.isEmpty()); model.refreshPending(false); model.action(CodeDialogAction.Save)
        assertEquals(CodeDialogAction.Save, model.state.value.effects.single().action)
    }
    @Test fun readOnlyAlternateEditorDoesNotWriteBackAndClearsPendingOnCancel() = scenario {
        val model = vm(show = true); runCurrent(); model.action(CodeDialogAction.Editor)
        assertTrue(model.state.value.editorReadOnly); model.close(); assertFalse(model.state.value.finished)
        model.editorResult("bad"); assertEquals("abc ABC abc", model.state.value.original)
        assertFalse(model.state.value.editorPending); assertTrue(model.state.value.showingAlternate)
    }
    @Test fun sourceEditorPublishesOriginalExactlyOnceWithoutClosingPreview() = scenario {
        val model = vm(source = true, show = true); runCurrent(); model.action(CodeDialogAction.Editor)
        val effect = model.state.value.effects.single(); model.consume(effect.id); model.editorResult("new", 2)
        assertEquals("new", model.state.value.original); assertNull(model.state.value.alternate)
        assertFalse(model.state.value.finished); assertFalse(model.state.value.showingAlternate)
        assertEquals(CodeDialogAction.EditorSaved, model.state.value.effects.single().action)
        model.editorResult("duplicate"); assertEquals("new", model.state.value.original)
    }
    @Test fun pendingEditorAndEffectsRestoreButConsumedEffectsDoNotRepeat() = scenario {
        val store = Store(); val saved = SavedStateHandle(); val model = vm(store, saved); runCurrent()
        model.action(CodeDialogAction.Editor); runCurrent(); val effect = model.state.value.effects.single(); model.consume(effect.id)
        model.stop(); val restored = vm(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        runCurrent(); assertTrue(restored.state.value.editorPending); assertTrue(restored.state.value.effects.isEmpty())
        restored.editorResult(null); assertFalse(restored.state.value.editorPending)
    }
    @Test fun failuresRetainDraftAndRetryLoadsWithoutFinishing() = scenario {
        val store = Store(); store.failRead = true; val model = vm(store); runCurrent()
        assertFalse(model.state.value.loaded); assertEquals("read failure", model.state.value.error)
        store.failRead = false; model.load(); runCurrent(); model.text("keep", 4, 4); store.failWrite = true
        runCurrent(); model.flushDraft(); assertEquals("keep", model.state.value.original)
        assertEquals("disk full", model.state.value.error); assertFalse(model.state.value.finished)
        store.failWrite = false; model.flushDraft(); assertEquals("keep", store.disk.values.single().original)
    }
    @Test fun manualActionsRequireSourceCapabilityAndEnabledHost() = scenario {
        val model = vm(source = true); runCurrent(); model.action(CodeDialogAction.Manual)
        assertTrue(model.state.value.effects.isEmpty()); model.action(CodeDialogAction.Manual, true)
        model.action(CodeDialogAction.Manual, true); assertEquals(1, model.state.value.effects.size)
        val readOnly = vm(editable = false); runCurrent(); readOnly.preview(true); readOnly.action(CodeDialogAction.Editor)
        readOnly.action(CodeDialogAction.Save); assertTrue(readOnly.state.value.effects.isEmpty())
    }
    @Test fun newestSearchWinsAndFinishedRestoreNeverReloadsOrRepublishes() = scenario {
        val store = Store(); val saved = SavedStateHandle(); val model = vm(store, saved); runCurrent()
        model.searchOpen(true); model.search("abc"); model.search("not found"); runCurrent()
        assertTrue(model.state.value.matches.isEmpty()); assertEquals(-1, model.state.value.matchIndex)
        model.selection(-10, 999); assertEquals(0, model.state.value.selectionStart)
        assertEquals(model.state.value.original.length, model.state.value.selectionEnd)
        model.close(); model.stop(); store.failRead = true
        val restored = vm(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })); runCurrent()
        assertTrue(restored.state.value.finished); assertNull(restored.state.value.error)
        restored.action(CodeDialogAction.Save); assertTrue(restored.state.value.effects.isEmpty())
    }

}
