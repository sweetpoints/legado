package io.legado.app.ui.highlight.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import io.legado.app.help.HighlightStyle
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HighlightRuleEditorViewModelTest {
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
    fun cannotSaveOrEditBeforeLoadAndLoadFailureRetriesWithoutBlankWrite() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadGate = CompletableDeferred() }
            val model = newModel(repo)
            val store = owned(model)
            try {
                model.save()
                model.change { it.copy(name = "early") }
                runCurrent()
                assertEquals(0, repo.saves)
                repo.loadGate!!.complete(Unit)
                repo.loadFails = true
                runCurrent()
                assertEquals("load", model.state.value.error)
                model.save()
                assertEquals(0, repo.saves)
                repo.loadFails = false
                repo.loadGate = null
                model.retry()
                runCurrent()
                assertEquals("Original", model.state.value.draft!!.rule.name)
                assertNull(model.state.value.error)
            } finally {
                store.clear()
            }
        }

    @Test
    fun repeatedSaveWhileInsertionPendingWritesOnceAndMetadataIsPreserved() =
        runTest(dispatcher) {
            val repo = Fake().apply { saveGate = CompletableDeferred() }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.change {
                    it.copy(
                        pattern = "new",
                        group = "Group",
                        applyToTitle = true,
                        applyToBody = false,
                    )
                }
                model.save()
                model.save()
                runCurrent()
                assertEquals(1, repo.saves)
                assertTrue(model.state.value.saving)
                model.change { it.copy(name = "ignored") }
                repo.saveGate!!.complete(Unit)
                runCurrent()
                assertEquals(HighlightRuleEditorEvent.Saved, model.state.value.event)
                assertTrue(model.state.value.finished)
                assertEquals(123L, model.state.value.draft!!.rule.timeoutMillisecond)
                assertEquals("Original", repo.saved!!.rule.name)
            } finally {
                store.clear()
            }
        }

    @Test
    fun invalidPatternLeavesDraftEditableAndDoesNotRefreshReader() =
        runTest(dispatcher) {
            val repo = Fake().apply { invalid = true }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.save()
                runCurrent()
                assertEquals(HighlightRuleEditorEvent.Invalid, model.state.value.event)
                assertFalse(model.state.value.saving)
                assertFalse(model.state.value.finished)
                assertNull(repo.saved)
                model.consume(HighlightRuleEditorEvent.Invalid)
                model.change { it.copy(pattern = "fixed") }
                assertEquals("fixed", model.state.value.draft!!.rule.pattern)
            } finally {
                store.clear()
            }
        }

    @Test
    fun cancelPendingLoadCannotPublishLateDraftAndNeverWritesRoom() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadGate = CompletableDeferred() }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.cancel()
                repo.loadGate!!.complete(Unit)
                runCurrent()
                assertTrue(model.state.value.finished)
                assertNull(model.state.value.draft)
                assertEquals(HighlightRuleEditorEvent.Close, model.state.value.event)
                assertEquals(0, repo.saves)
            } finally {
                store.clear()
            }
        }

    @Test
    fun largePatternAndStyleLiveInDiskDraftAndOnlySessionAndSmallColorFlagsAreSaved() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            try {
                runCurrent()
                val huge = "x".repeat(1200000)
                model.change { it.copy(pattern = huge, scope = "book", group = "G") }
                model.style(HighlightStyle(fill = 321, bold = true))
                model.color(8101, 123, true)
                model.flush()
                runCurrent()
                assertEquals(huge, repo.disk!!.rule.pattern)
                assertEquals(321, repo.disk!!.rule.style.fill)
                assertTrue(saved.keys().none { saved.get<Any?>(it) == huge })
                val restored = newModel(repo, copy(saved))
                val other = owned(restored)
                try {
                    runCurrent()
                    assertEquals(huge, restored.state.value.draft!!.rule.pattern)
                    assertEquals(
                        HighlightRuleColorDraft(8101, 123, true),
                        restored.state.value.color,
                    )
                    restored.closeColor()
                    assertNull(restored.state.value.color)
                } finally {
                    other.clear()
                }
            } finally {
                store.clear()
            }
        }

    @Test
    fun persistedSaveReceiptDeliversOnceAfterRestoreAndConsumedReceiptNeverReplays() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            runCurrent()
            model.save()
            runCurrent()
            val restoredSaved = copy(saved)
            val restored = newModel(repo, restoredSaved)
            val other = owned(restored)
            store.clear()
            try {
                runCurrent()
                assertEquals(HighlightRuleEditorEvent.Saved, restored.state.value.event)
                restored.consume(HighlightRuleEditorEvent.Saved)
                val next = newModel(repo, copy(restoredSaved))
                val last = owned(next)
                try {
                    runCurrent()
                    assertNull(next.state.value.event)
                    assertTrue(next.state.value.finished)
                    assertEquals(1, repo.saves)
                } finally {
                    last.clear()
                }
            } finally {
                other.clear()
            }
        }

    @Test
    fun styleChangesAndColorCancellationOnlyUpdateDraftAndMissingRuleQueuesDismiss() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.style(HighlightStyle(bold = true))
                model.openStyle()
                assertEquals(HighlightRuleEditorEvent.Style, model.state.value.event)
                assertEquals(0, repo.saves)
                model.color(8101, 0x11223344, true)
                model.closeColor()
                assertEquals(0, model.state.value.draft!!.rule.style.fill)
            } finally {
                store.clear()
            }
            val missing = newModel(Fake().apply { missing = true })
            val missingStore = owned(missing)
            try {
                runCurrent()
                assertEquals(HighlightRuleEditorEvent.Missing, missing.state.value.event)
            } finally {
                missingStore.clear()
            }
        }

    private fun newModel(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        HighlightRuleEditorViewModel(repo, saved, 9, null)

    private fun owned(model: HighlightRuleEditorViewModel) =
        ViewModelStore().apply { put("editor", model) }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Fake : HighlightRuleEditorRepository {
        var loadFails = false
        var invalid = false
        var missing = false
        var saves = 0
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var saved: HighlightRuleEditorDraft? = null
        var disk: HighlightRuleEditorDraft? = null

        override suspend fun initial(
            session: String,
            id: Long,
            seed: String?,
        ): HighlightRuleEditorDraft {
            loadGate?.await()
            if (loadFails) error("load")
            if (missing) throw MissingHighlightRuleException()
            return disk
                ?: HighlightRuleEditorDraft(
                    HighlightRuleDraft(
                        id = 9,
                        name = "Original",
                        pattern = "pattern",
                        timeoutMillisecond = 123,
                    )
                )
        }

        override suspend fun draft(session: String, draft: HighlightRuleEditorDraft) {
            disk = draft
        }

        override suspend fun save(
            session: String,
            draft: HighlightRuleEditorDraft,
        ): HighlightRuleEditorDraft {
            saves++
            saveGate?.await()
            if (invalid) throw InvalidHighlightRuleException(draft.rule.pattern)
            return draft.copy(savedRuleId = 9, revision = draft.revision + 1).also {
                saved = it
                disk = it
            }
        }
    }
}
