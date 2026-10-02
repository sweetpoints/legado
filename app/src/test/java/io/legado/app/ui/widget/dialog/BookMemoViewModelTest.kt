package io.legado.app.ui.widget.dialog

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookMemoViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<BookMemoViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Fake : BookMemoRepository {
        val memo = MutableStateFlow<BookMemoSnapshot?>(BookMemoSnapshot("Original", 10))
        val drafts = mutableMapOf<String, BookMemoDraft>(); val saves = mutableListOf<String>()
        var saveGate: CompletableDeferred<Unit>? = null; var fail = false; var draftFail = false
        override fun observe(bookUrl: String) = memo
        override suspend fun save(bookUrl: String, content: String): BookMemoSnapshot {
            saveGate?.await(); if (fail) error("save failed"); saves += content
            return BookMemoSnapshot(content, (memo.value?.updatedAt ?: 0) + 1).also { memo.value = it }
        }
        override suspend fun readDraft(id: String) = drafts[id]
        override suspend fun writeDraft(id: String, draft: BookMemoDraft) {
            if (draftFail) error("disk full")
            if ((drafts[id]?.revision ?: -1) <= draft.revision) drafts[id] = draft
        }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = BookMemoViewModel(repo, saved, "book").also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun observedMemoLoadsAndLiveUpdatesNeverOverwriteEditingDraft() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); assertTrue(model.state.value.loaded)
        model.edit(); model.text("Typed", 2, 4); runCurrent(); repo.memo.value = BookMemoSnapshot("External", 20); runCurrent()
        assertEquals("Typed", model.state.value.draft); assertEquals("External", model.state.value.memo?.content)
        assertEquals(2, model.state.value.selectionStart); assertEquals(4, model.state.value.selectionEnd)
    }
    @Test fun largeDiskDraftRestoresWithoutPuttingBodyIntoSavedStateOrRepeatingEdit() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent()
        val body = "正文".repeat(200000); model.edit(); model.text(body, 17, 19); model.edit(); runCurrent(); model.flushDraft()
        assertEquals(body, repo.drafts[model.draftId]?.content); assertTrue(saved.keys().none { saved.get<Any?>(it) == body })
        val restored = model(repo, copy(saved)); runCurrent(); assertTrue(restored.state.value.editing)
        assertEquals(body, restored.state.value.draft); assertEquals(17, restored.state.value.selectionStart); assertEquals(19, restored.state.value.selectionEnd)
    }
    @Test fun saveIsSingleFlightAndAwaitsPersistenceBeforeLeavingEditMode() = test {
        val repo = Fake().apply { saveGate = CompletableDeferred() }; val model = model(repo); runCurrent(); model.edit(); model.text("Changed")
        model.save(); model.save(); runCurrent(); model.close(); assertTrue(model.state.value.saving); assertTrue(model.state.value.editing); assertFalse(model.state.value.finished)
        repo.saveGate!!.complete(Unit); runCurrent(); assertEquals(listOf("Changed"), repo.saves)
        assertFalse(model.state.value.editing); assertEquals(BookMemoSnapshot("Changed", 11), model.state.value.memo)
    }
    @Test fun failedSaveKeepsDraftAndRetryPersistsSameContent() = test {
        val repo = Fake().apply { fail = true }; val model = model(repo); runCurrent(); model.edit(); model.text("Keep")
        model.save(); runCurrent(); assertTrue(model.state.value.editing); assertEquals("Keep", model.state.value.draft); assertEquals("save failed", model.state.value.error)
        repo.fail = false; model.save(); runCurrent(); assertEquals(listOf("Keep"), repo.saves); assertFalse(model.state.value.editing)
    }
    @Test fun cancelEditingWritesNoMemoAndRestoredDraftStaysOutOfEditMode() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.edit(); model.text("Discard")
        model.cancelEdit(); runCurrent(); val restored = model(repo, copy(saved)); runCurrent()
        assertFalse(restored.state.value.editing); assertTrue(repo.saves.isEmpty()); assertEquals("Original", restored.state.value.memo?.content)
    }
    @Test fun closeEditingRequiresDiscardConfirmationAndCancelRetainsDraft() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.edit(); model.text("Draft"); model.close()
        assertEquals(BookMemoConfirmation.Discard, model.state.value.confirmation); assertFalse(model.state.value.finished)
        model.cancelConfirmation(); assertTrue(model.state.value.editing); model.close(); model.confirm(); runCurrent()
        assertTrue(model.state.value.finished); assertTrue(repo.saves.isEmpty())
    }
    @Test fun clearingRequiresConfirmationAndKeepsDatedEmptyMemo() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent(); model.requestClear()
        val restored = model(repo, copy(saved)); runCurrent(); assertEquals(BookMemoConfirmation.Clear, restored.state.value.confirmation)
        restored.confirm(); runCurrent(); assertEquals(listOf(""), repo.saves); assertEquals(BookMemoSnapshot("", 11), restored.state.value.memo)
        assertNull(restored.state.value.confirmation); assertFalse(restored.state.value.finished)
    }
    @Test fun failedLifecycleCheckpointReportsErrorAndKeepsEditableBody() = test {
        val repo = Fake(); val model = model(repo); runCurrent(); model.edit(); model.text("Body"); runCurrent(); repo.draftFail = true
        model.flushDraft(); assertEquals("disk full", model.state.value.error); assertTrue(model.state.value.editing); assertEquals("Body", model.state.value.draft)
    }
    @Test fun finishedRestoreNeverLoadsOrWritesDraftAndCannotReopen() = test {
        val repo = Fake(); val model = model(repo, SavedStateHandle(mapOf("memo.finished" to true)))
        model.edit(); model.text("ignored"); model.save(); model.requestClear(); runCurrent()
        assertTrue(model.state.value.finished); assertTrue(repo.saves.isEmpty()); assertTrue(repo.drafts.isEmpty()); assertFalse(model.state.value.editing)
    }
}
