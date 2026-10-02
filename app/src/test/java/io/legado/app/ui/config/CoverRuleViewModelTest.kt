package io.legado.app.ui.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.CoverRuleDraft
import io.legado.app.data.repository.CoverRuleRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoverRuleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun loadShowsStoredRuleAndLateLoadNeverOverwritesUserEdits() = runTest(dispatcher) {
        val repository = FakeRepository()
        val loaded = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        assertEquals(repository.rule, loaded.state.value.draft)
        assertFalse(loaded.state.value.isLoading)
        val pending = CompletableDeferred<CoverRuleDraft>()
        repository.loadResult = { pending.await() }
        val editing = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        editing.setSearchUrl("typed URL")
        editing.setCoverRule("typed rule")
        pending.complete(repository.rule)
        runCurrent()
        assertEquals("typed URL", editing.state.value.draft.searchUrl)
        assertEquals("typed rule", editing.state.value.draft.coverRule)
    }

    @Test fun restoredDraftAvoidsReloadAndRetainsDisabledAndEmptyFields() = runTest(dispatcher) {
        val repository = FakeRepository()
        val handle = SavedStateHandle()
        val original = CoverRuleViewModel(repository, handle)
        runCurrent()
        original.setEnabled(false)
        original.setSearchUrl("")
        original.setCoverRule("draft")
        val restored = CoverRuleViewModel(repository,
            SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        runCurrent()
        assertEquals(1, repository.loadCount)
        assertEquals(CoverRuleDraft(false, "", "draft"), restored.state.value.draft)
        assertFalse(restored.state.value.isLoading)
    }

    @Test fun whitespaceValidationDoesNotSaveOrDismissEvenWhenDisabled() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        model.setEnabled(false)
        model.setSearchUrl("  ")
        model.save()
        runCurrent()
        assertTrue(model.state.value.showValidation)
        assertNull(repository.saved)
        assertFalse(model.state.value.finished)
        model.setSearchUrl("url")
        model.setCoverRule("\n")
        model.save()
        runCurrent()
        assertNull(repository.saved)
    }

    @Test fun saveUsesExactDraftAndRepeatedClicksPersistOnce() = runTest(dispatcher) {
        val repository = FakeRepository()
        val pending = CompletableDeferred<Unit>()
        repository.saveAction = { pending.await() }
        val model = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        val draft = CoverRuleDraft(false, " url ", " rule ")
        model.setEnabled(draft.enabled)
        model.setSearchUrl(draft.searchUrl)
        model.setCoverRule(draft.coverRule)
        model.save()
        model.save()
        model.cancel()
        runCurrent()
        assertEquals(1, repository.saveCount)
        assertEquals(draft, repository.saved)
        assertFalse(model.state.value.finished)
        pending.complete(Unit)
        runCurrent()
        assertTrue(model.state.value.finished)
        assertFalse(model.state.value.isSaving)
    }

    @Test fun failedSaveKeepsDraftAndAllowsRetry() = runTest(dispatcher) {
        val repository = FakeRepository()
        repository.saveAction = { error("write failed") }
        val model = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        model.setCoverRule("edited rule")
        model.save()
        runCurrent()
        assertEquals("write failed", model.state.value.error)
        assertEquals("edited rule", model.state.value.draft.coverRule)
        assertFalse(model.state.value.finished)
        repository.saveAction = {}
        model.save()
        runCurrent()
        assertTrue(model.state.value.finished)
        assertEquals(2, repository.saveCount)
    }

    @Test fun deleteRemovesOverrideOnceAndCancelNeverPersists() = runTest(dispatcher) {
        val repository = FakeRepository()
        val cancel = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        cancel.setSearchUrl("unsaved")
        cancel.cancel()
        cancel.save()
        cancel.delete()
        runCurrent()
        assertTrue(cancel.state.value.finished)
        assertEquals(0, repository.saveCount)
        assertEquals(0, repository.deleteCount)
        val delete = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        delete.delete()
        delete.delete()
        runCurrent()
        assertEquals(1, repository.deleteCount)
        assertTrue(delete.state.value.finished)
    }

    @Test fun loadFailureCanRetryAndCancelStopsPendingLoad() = runTest(dispatcher) {
        val repository = FakeRepository()
        repository.loadResult = { error("read failed") }
        val model = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        assertEquals("read failed", model.state.value.error)
        assertFalse(model.state.value.isLoading)
        repository.loadResult = { repository.rule }
        model.load()
        runCurrent()
        assertEquals(repository.rule, model.state.value.draft)
        assertNull(model.state.value.error)
        val pending = CompletableDeferred<CoverRuleDraft>()
        repository.loadResult = { pending.await() }
        val cancelled = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        cancelled.cancel()
        pending.complete(repository.rule)
        runCurrent()
        assertTrue(cancelled.state.value.finished)
        assertEquals(CoverRuleDraft(), cancelled.state.value.draft)
    }

    @Test fun failedDeleteStaysOpenAndCanRetryWithoutSavingDraft() = runTest(dispatcher) {
        val repository = FakeRepository()
        repository.deleteAction = { error("delete failed") }
        val model = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        model.setSearchUrl("unsaved edit")
        model.delete()
        runCurrent()
        assertEquals("delete failed", model.state.value.error)
        assertFalse(model.state.value.finished)
        assertEquals("unsaved edit", model.state.value.draft.searchUrl)
        repository.deleteAction = {}
        model.delete()
        runCurrent()
        assertTrue(model.state.value.finished)
        assertEquals(2, repository.deleteCount)
        assertEquals(0, repository.saveCount)
    }

    @Test fun cancelledLoadIgnoresLateNonCooperativeResultAndDoesNotPersistDraft() = runTest(dispatcher) {
        val repository = FakeRepository()
        val pending = CompletableDeferred<CoverRuleDraft>()
        repository.loadResult = { withContext(NonCancellable) { pending.await() } }
        val handle = SavedStateHandle()
        val model = CoverRuleViewModel(repository, handle)
        runCurrent()
        model.cancel()
        val cancelled = model.state.value
        pending.complete(repository.rule)
        runCurrent()
        assertEquals(cancelled, model.state.value)
        assertTrue(model.state.value.finished)
        assertEquals(CoverRuleDraft(), model.state.value.draft)
        assertTrue(handle.keys().isEmpty())
    }

    @Test fun cancelledLoadIgnoresLateNonCooperativeFailure() = runTest(dispatcher) {
        val repository = FakeRepository()
        val pending = CompletableDeferred<Unit>()
        repository.loadResult = {
            withContext(NonCancellable) { pending.await() }
            error("late read failure")
        }
        val model = CoverRuleViewModel(repository, SavedStateHandle())
        runCurrent()
        model.cancel()
        val cancelled = model.state.value
        pending.complete(Unit)
        runCurrent()
        assertEquals(cancelled, model.state.value)
        assertNull(model.state.value.error)
    }

    private class FakeRepository : CoverRuleRepository {
        val rule = CoverRuleDraft(true, "stored URL", "stored rule")
        var loadCount = 0
        var saveCount = 0
        var deleteCount = 0
        var saved: CoverRuleDraft? = null
        var loadResult: suspend () -> CoverRuleDraft = { rule }
        var saveAction: suspend () -> Unit = {}
        var deleteAction: suspend () -> Unit = {}
        override suspend fun load(): CoverRuleDraft { loadCount++; return loadResult() }
        override suspend fun save(draft: CoverRuleDraft) { saveCount++; saved = draft; saveAction() }
        override suspend fun delete() { deleteCount++; deleteAction() }
    }
}
