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

    @After
    fun cleanup() {
        models.forEach { it.stop() }
        Dispatchers.resetMain()
    }

    private fun scenario(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            models.forEach { it.stop() }
            runCurrent()
        }
    }

    private class Store : CodeDialogRepository {
        val disk = mutableMapOf<String, CodeDialogDraft>()
        var readGate: CompletableDeferred<Unit>? = null
        var failRead = false
        var failWrite = false
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun read(id: String): CodeDialogDraft? {
            readGate?.await()
            if (failRead) error("read failure")
            return disk[id]
        }

        override suspend fun write(id: String, draft: CodeDialogDraft) {
            writeGate?.await()
            if (failWrite) error("disk full")
            if ((disk[id]?.revision ?: -1) <= draft.revision) disk[id] = draft
        }
    }

    private fun vm(
        store: Store = Store(),
        saved: SavedStateHandle = SavedStateHandle(),
        editable: Boolean = true,
        source: Boolean = false,
        original: String = "abc ABC abc",
        alternate: String? = "derived",
        show: Boolean = false,
    ) =
        CodeDialogViewModel(
                store,
                saved,
                original,
                alternate,
                editable,
                source,
                show,
                Dispatchers.Main,
            )
            .also { models += it }

    @Test
    fun delayedRestoreDisablesEditingAndReusesDiskDraft() = scenario {
        val store = Store()
        val saved = SavedStateHandle()
        val model = vm(store, saved)
        runCurrent()
        model.text("latest", 6, 6)
        runCurrent()
        model.stop()
        store.readGate = CompletableDeferred()
        val restored =
            vm(
                store,
                SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }),
                original = "old",
            )
        runCurrent()
        restored.text("early", 0, 0)
        assertFalse(restored.state.value.loaded)
        store.readGate!!.complete(Unit)
        runCurrent()
        assertEquals("latest", restored.state.value.original)
        assertEquals(6, restored.state.value.selectionStart)
    }

    @Test
    fun largeDraftAndAlternateNeverEnterSavedState() = scenario {
        val store = Store()
        val saved = SavedStateHandle()
        val model = vm(store, saved)
        runCurrent()
        val text = "a".repeat(500_000)
        model.text(text, text.length, 3)
        runCurrent()
        model.flushDraft()
        assertEquals(text, store.disk.values.single().original)
        assertEquals(text.length, model.state.value.selectionStart)
        assertEquals(3, model.state.value.selectionEnd)
        assertTrue(saved.keys().all { ((saved.get<Any>(it) as? String)?.length ?: 0) < 1000 })
    }

    @Test
    fun alternatePreviewNeverOverwritesOriginalAndCannotBeEdited() = scenario {
        val model = vm(source = true)
        runCurrent()
        model.text("edited", 6, 6)
        model.preview(true)
        assertEquals("derived", model.state.value.displayed)
        model.text("bad", 0, 0)
        assertEquals("edited", model.state.value.original)
        model.preview(false)
        assertEquals("edited", model.state.value.displayed)
    }

    @Test
    fun removedAlternateSurvivesRestoreWithoutResurrectingInitialPreview() = scenario {
        val store = Store()
        val saved = SavedStateHandle()
        val model = vm(store, saved, show = true)
        runCurrent()
        model.alternate(null)
        runCurrent()
        model.stop()
        val restored =
            vm(
                store,
                SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }),
                show = true,
            )
        runCurrent()
        assertNull(restored.state.value.alternate)
        assertFalse(restored.state.value.showingAlternate)
    }

    @Test
    fun literalSearchWrapsCaseInsensitiveAndTracksSelection() = scenario {
        val model = vm()
        runCurrent()
        model.searchOpen(true)
        model.search("abc")
        runCurrent()
        assertEquals(3, model.state.value.matches.size)
        assertEquals(0, model.state.value.matchIndex)
        model.match(-1)
        assertEquals(2, model.state.value.matchIndex)
        assertEquals(8, model.state.value.selectionStart)
        model.match(3)
        assertEquals(0, model.state.value.matchIndex)
        model.search("[")
        runCurrent()
        assertEquals(-1, model.state.value.matchIndex)
        assertTrue(model.state.value.matches.isEmpty())
        model.searchOpen(false)
        assertTrue(model.state.value.matches.isEmpty())
    }

    @Test
    fun refreshPendingBlocksEditsActionsAndCloseWithoutLosingOriginal() = scenario {
        val model = vm(source = true)
        runCurrent()
        model.refreshPending(true)
        model.text("bad", 0, 0)
        model.preview(true)
        model.action(CodeDialogAction.Save)
        model.close()
        assertEquals("abc ABC abc", model.state.value.original)
        assertFalse(model.state.value.finished)
        assertTrue(model.state.value.effects.isEmpty())
        model.refreshPending(false)
        model.action(CodeDialogAction.Save)
        assertEquals(CodeDialogAction.Save, model.state.value.effects.single().action)
    }

    @Test
    fun readOnlyAlternateEditorDoesNotWriteBackAndClearsPendingOnCancel() = scenario {
        val model = vm(show = true)
        runCurrent()
        model.action(CodeDialogAction.Editor)
        assertTrue(model.state.value.editorReadOnly)
        model.close()
        assertFalse(model.state.value.finished)
        model.editorResult("bad")
        assertEquals("abc ABC abc", model.state.value.original)
        assertFalse(model.state.value.editorPending)
        assertTrue(model.state.value.showingAlternate)
    }

    @Test
    fun sourceEditorPublishesOriginalExactlyOnceWithoutClosingPreview() = scenario {
        val model = vm(source = true, show = true)
        runCurrent()
        model.action(CodeDialogAction.Editor)
        val effect = model.state.value.effects.single()
        model.consume(effect.id)
        model.editorResult("new", 2)
        assertEquals("new", model.state.value.original)
        assertNull(model.state.value.alternate)
        assertFalse(model.state.value.finished)
        assertFalse(model.state.value.showingAlternate)
        assertEquals(CodeDialogAction.EditorSaved, model.state.value.effects.single().action)
        assertTrue(
            "An undelivered return must not allow saving an unrefreshed source",
            model.state.value.busy,
        )
        model.action(CodeDialogAction.Save)
        model.close()
        assertFalse(model.state.value.finished)
        assertEquals(
            listOf(CodeDialogAction.EditorSaved),
            model.state.value.effects.map { it.action },
        )
        model.editorResult("duplicate")
        assertEquals("new", model.state.value.original)
    }

    @Test
    fun pendingEditorAndEffectsRestoreButConsumedEffectsDoNotRepeat() = scenario {
        val store = Store()
        val saved = SavedStateHandle()
        val model = vm(store, saved)
        runCurrent()
        model.action(CodeDialogAction.Editor)
        runCurrent()
        val effect = model.state.value.effects.single()
        model.consume(effect.id)
        model.stop()
        val restored =
            vm(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        runCurrent()
        assertTrue(restored.state.value.editorPending)
        assertTrue(restored.state.value.effects.isEmpty())
        restored.editorResult(null)
        assertFalse(restored.state.value.editorPending)
    }

    @Test
    fun failuresRetainDraftAndRetryLoadsWithoutFinishing() = scenario {
        val store = Store()
        store.failRead = true
        val model = vm(store)
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertEquals("read failure", model.state.value.error)
        store.failRead = false
        model.load()
        runCurrent()
        model.text("keep", 4, 4)
        store.failWrite = true
        runCurrent()
        model.flushDraft()
        assertEquals("keep", model.state.value.original)
        assertEquals("disk full", model.state.value.error)
        assertFalse(model.state.value.finished)
        store.failWrite = false
        model.flushDraft()
        assertEquals("keep", store.disk.values.single().original)
    }

    @Test
    fun manualActionsRequireSourceCapabilityAndEnabledHost() = scenario {
        val model = vm(source = true)
        runCurrent()
        model.action(CodeDialogAction.Manual)
        assertTrue(model.state.value.effects.isEmpty())
        model.action(CodeDialogAction.Manual, true)
        model.action(CodeDialogAction.Manual, true)
        assertEquals(1, model.state.value.effects.size)
        val readOnly = vm(editable = false)
        runCurrent()
        readOnly.preview(true)
        readOnly.action(CodeDialogAction.Editor)
        readOnly.action(CodeDialogAction.Save)
        assertTrue(readOnly.state.value.effects.isEmpty())
    }

    @Test
    fun newestSearchWinsAndFinishedRestoreNeverReloadsOrRepublishes() = scenario {
        val store = Store()
        val saved = SavedStateHandle()
        val model = vm(store, saved)
        runCurrent()
        model.searchOpen(true)
        model.search("abc")
        model.search("not found")
        runCurrent()
        assertTrue(model.state.value.matches.isEmpty())
        assertEquals(-1, model.state.value.matchIndex)
        model.selection(-10, 999)
        assertEquals(0, model.state.value.selectionStart)
        assertEquals(model.state.value.original.length, model.state.value.selectionEnd)
        model.close()
        model.stop()
        store.failRead = true
        val restored =
            vm(store, SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        runCurrent()
        assertTrue(restored.state.value.finished)
        assertNull(restored.state.value.error)
        restored.action(CodeDialogAction.Save)
        assertTrue(restored.state.value.effects.isEmpty())
    }

    @Test
    fun sourceEditorRefreshRestoresRequestedPreviewWithoutReplacingOriginal() = scenario {
        val model = vm(source = true, show = true)
        runCurrent()
        model.action(CodeDialogAction.Editor)
        model.consume(model.state.value.effects.single().id)
        model.editorResult("edited", 3)
        assertFalse(model.state.value.showingAlternate)
        assertTrue(model.state.value.busy)
        // CodeDialogRoute consumes the one return effect before its host refreshes
        // replacements; model.alternate below represents that delivered response.
        val returned = model.state.value.effects.single()
        assertEquals(CodeDialogAction.EditorSaved, returned.action)
        model.consume(returned.id)
        model.alternate("replaced edited")
        assertTrue(model.state.value.showingAlternate)
        assertEquals("replaced edited", model.state.value.displayed)
        assertEquals("edited", model.state.value.original)
        model.preview(false)
        model.alternate("later")
        assertFalse(model.state.value.showingAlternate)
        assertEquals("edited", model.state.value.displayed)
    }

    private class Transfer : CodeDialogTransferRepository {
        val files = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        var failRead = false
        var writeGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun write(text: String): String {
            writeGate?.await()
            val path = "input-${files.size}"
            files[path] = text
            return path
        }

        override suspend fun read(path: String): String {
            readGate?.await()
            if (failRead) error("missing result")
            return files[path] ?: error("missing result")
        }

        override suspend fun delete(vararg paths: String?) {
            paths.filterNotNull().forEach {
                files.remove(it)
                deleted += it
            }
        }
    }

    @Test
    fun preparedEditorPathRestoresWithoutRewritingOrRelauchingConsumedEffect() = scenario {
        val store = Store()
        val files = Transfer()
        val saved = SavedStateHandle()
        val model =
            CodeDialogViewModel(
                    store,
                    saved,
                    "large".repeat(100000),
                    "derived",
                    true,
                    true,
                    transfer = files,
                )
                .also { models += it }
        runCurrent()
        model.action(CodeDialogAction.Editor)
        model.prepareEditor()
        runCurrent()
        assertTrue(model.state.value.editorPrepared)
        assertEquals("large".repeat(100000), files.files[model.state.value.editorPath])
        model.consume(model.state.value.effects.single().id)
        model.stop()
        val restored =
            CodeDialogViewModel(
                    store,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    "",
                    null,
                    true,
                    true,
                    transfer = files,
                )
                .also { models += it }
        runCurrent()
        assertTrue(restored.state.value.editorPending)
        assertTrue(restored.state.value.editorPrepared)
        assertTrue(restored.state.value.effects.isEmpty())
        assertEquals(1, files.files.size)
        files.files["output"] = "returned"
        restored.editorReturned(true, null, "output", 4)
        runCurrent()
        assertEquals("returned", restored.state.value.original)
        assertEquals(4, restored.state.value.selectionStart)
        assertEquals(
            listOf(CodeDialogAction.EditorSaved),
            restored.state.value.effects.map { it.action },
        )
        assertTrue(files.files.isEmpty())
        assertFalse(restored.state.value.finished)
    }

    @Test
    fun cancelledAndReadOnlyEditorResultsCleanBothPathsWithoutChangingOriginal() = scenario {
        for (readOnly in listOf(false, true)) {
            val files = Transfer()
            val model =
                CodeDialogViewModel(
                        Store(),
                        SavedStateHandle(),
                        "original",
                        "derived",
                        true,
                        false,
                        readOnly,
                        transfer = files,
                    )
                    .also { models += it }
            runCurrent()
            model.action(CodeDialogAction.Editor)
            model.prepareEditor()
            runCurrent()
            assertEquals(
                if (readOnly) "derived" else "original",
                files.files[model.state.value.editorPath],
            )
            model.consume(model.state.value.effects.single().id)
            files.files["output"] = "discarded"
            model.editorReturned(readOnly, null, "output", 3)
            runCurrent()
            assertEquals("original", model.state.value.original)
            assertFalse(model.state.value.editorPending)
            assertTrue(files.files.isEmpty())
            assertTrue(model.state.value.effects.isEmpty())
        }
    }

    @Test
    fun failedTransferReadKeepsDraftAndRetainsTransferForRecovery() = scenario {
        val files = Transfer()
        val model =
            CodeDialogViewModel(
                    Store(),
                    SavedStateHandle(),
                    "keep",
                    null,
                    true,
                    false,
                    transfer = files,
                )
                .also { models += it }
        runCurrent()
        model.action(CodeDialogAction.Editor)
        model.prepareEditor()
        runCurrent()
        model.consume(model.state.value.effects.single().id)
        files.failRead = true
        model.editorReturned(true, null, "missing", 0)
        runCurrent()
        assertEquals("keep", model.state.value.original)
        assertEquals("missing result", model.state.value.error)
        assertFalse(model.state.value.editorPending)
        assertEquals("keep", files.files.values.single())
        model.action(CodeDialogAction.Editor)
        model.prepareEditor()
        runCurrent()
        assertTrue(model.state.value.editorPrepared)
    }

    @Test
    fun resultArrivingBeforeDraftRestoreWaitsForLoadAndAppliesOnce() = scenario {
        val store = Store()
        val files = Transfer()
        val saved = SavedStateHandle()
        val first =
            CodeDialogViewModel(store, saved, "original", null, true, true, transfer = files).also {
                models += it
            }
        runCurrent()
        first.action(CodeDialogAction.Editor)
        first.prepareEditor()
        runCurrent()
        first.consume(first.state.value.effects.single().id)
        first.stop()
        store.readGate = CompletableDeferred()
        files.files["output"] = "edited"
        val restored =
            CodeDialogViewModel(
                    store,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    "old",
                    null,
                    true,
                    true,
                    transfer = files,
                )
                .also { models += it }
        runCurrent()
        restored.editorReturned(true, null, "output", 2)
        runCurrent()
        assertTrue(files.files.containsKey("output"))
        assertFalse(restored.state.value.loaded)
        store.readGate!!.complete(Unit)
        runCurrent()
        assertEquals("edited", restored.state.value.original)
        assertTrue(files.files.isEmpty())
        assertEquals(1, restored.state.value.effects.size)
        restored.editorReturned(true, "duplicate", null, 0)
        runCurrent()
        assertEquals("edited", restored.state.value.original)
    }

    @Test
    fun completedDurableResultSurvivesProcessSnapshotEvenWhenOutputWasAlreadyCleaned() = scenario {
        val store = Store()
        val files = Transfer()
        val saved = SavedStateHandle()
        val first =
            CodeDialogViewModel(store, saved, "original", "derived", true, true, transfer = files)
                .also { models += it }
        runCurrent()
        first.action(CodeDialogAction.Editor)
        first.prepareEditor()
        runCurrent()
        first.consume(first.state.value.effects.single().id)
        first.stop()
        saved["editorReturning"] = true
        saved["editorAccepted"] = true
        saved["editorOutputPath"] = "cleaned-output"
        saved["editorCursor"] = 2
        val id = saved.get<String>("session")!!
        val previous = store.disk[id]!!
        store.disk[id] = CodeDialogDraft("durable result", null, previous.revision + 1)
        val restored =
            CodeDialogViewModel(
                    store,
                    SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
                    "old",
                    null,
                    true,
                    true,
                    transfer = files,
                )
                .also { models += it }
        runCurrent()
        assertEquals("durable result", restored.state.value.original)
        assertEquals(2, restored.state.value.selectionStart)
        assertNull(restored.state.value.error)
        assertEquals(
            listOf(CodeDialogAction.EditorSaved),
            restored.state.value.effects.map { it.action },
        )
        assertFalse(restored.state.value.editorPending)
    }

    @Test
    fun cancelledReturnReadFinishesDurablyWithoutPublishingFromClearedVm() = scenario {
        cancelledReturn(writePhase = false)
    }

    @Test
    fun cancelledReturnWriteFinishesDurablyWithoutPublishingFromClearedVm() = scenario {
        cancelledReturn(writePhase = true)
    }

    private suspend fun TestScope.cancelledReturn(writePhase: Boolean) {
        val store = Store()
        val files = Transfer()
        val saved = SavedStateHandle()
        val model =
            CodeDialogViewModel(store, saved, "original", null, true, true, transfer = files).also {
                models += it
            }
        runCurrent()
        model.action(CodeDialogAction.Editor)
        model.prepareEditor()
        runCurrent()
        model.consume(model.state.value.effects.single().id)
        files.files["output"] = "returned result"
        val gate = CompletableDeferred<Unit>()
        if (writePhase) store.writeGate = gate else files.readGate = gate
        model.editorReturned(true, null, "output", 4)
        runCurrent()
        val snapshot = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        model.stop()
        runCurrent()
        assertTrue(files.files.containsKey("output"))
        gate.complete(Unit)
        runCurrent()
        assertEquals("returned result", store.disk.values.single().original)
        assertTrue(files.files.isEmpty())
        assertEquals("original", model.state.value.original)
        assertTrue(model.state.value.effects.isEmpty())
        store.writeGate = null
        files.readGate = null
        val restored =
            CodeDialogViewModel(store, snapshot, "old", null, true, true, transfer = files).also {
                models += it
            }
        runCurrent()
        assertEquals("returned result", restored.state.value.original)
        assertEquals(
            listOf(CodeDialogAction.EditorSaved),
            restored.state.value.effects.map { it.action },
        )
        assertFalse(restored.state.value.editorPending)
    }
}
