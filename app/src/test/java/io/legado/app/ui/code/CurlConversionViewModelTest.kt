package io.legado.app.ui.code

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import io.legado.app.model.analyzeRule.CurlAnalyzeUrlConverter
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CurlConversionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun savedCopy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), canInsert: Boolean = true) = CurlConversionViewModel(repo, saved, "seed", canInsert)
    @Test fun initialDirectionAndCursorRestoreFromRepositoryWhileSavedStateContainsNoLargeText() = runTest(dispatcher) {
        val repo = Fake().apply { initial = CurlConversionDraft("x".repeat(1_100_000), "y".repeat(1_100_000), CurlDirection.AnalyzeToCurl) }
        val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent()
        first.input(first.state.value.draft.input, 20, 60); runCurrent()
        val restored = model(repo, savedCopy(saved)); runCurrent()
        assertEquals(first.state.value.draft, restored.state.value.draft)
        assertTrue(saved.keys().associateWith { saved.get<Any?>(it) }.toString().length < 1000)
        assertEquals(20, restored.state.value.draft.selectionStart); assertEquals(60, restored.state.value.draft.selectionEnd)
    }
    @Test fun changingDirectionClearsOutputButEditingInputKeepsGeneratedOutputAndNeverAutoConverts() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); runCurrent()
        model.input("edited", 2, 4); runCurrent(); assertEquals("output", model.state.value.draft.output)
        assertEquals(0, repo.conversions); model.direction(CurlDirection.AnalyzeToCurl); runCurrent()
        assertEquals("edited", model.state.value.draft.input); assertEquals("", model.state.value.draft.output)
    }
    @Test fun canceledLateConversionCannotReplaceNewInputOrDirection() = runTest(dispatcher) {
        val repo = Fake().apply { conversionGate = CompletableDeferred() }; val model = model(repo); runCurrent()
        model.convert(); runCurrent(); model.input("new command", 1, 1); runCurrent()
        repo.conversionGate!!.complete(Unit); runCurrent()
        assertEquals("new command", model.state.value.draft.input); assertEquals("output", model.state.value.draft.output); assertFalse(model.state.value.converting)
    }
    @Test fun conversionUsesExactDirectionAndFailureClearsOutputWithTypedReasonAndDetail() = runTest(dispatcher) {
        val repo = Fake().apply { conversionFailure = true }; val model = model(repo); runCurrent()
        model.convert(); runCurrent(); assertEquals("", model.state.value.draft.output)
        val effect = model.state.value.effects.single(); assertEquals(CurlNotice.Conversion, effect.notice)
        assertEquals(CurlAnalyzeUrlConverter.ErrorReason.UNSUPPORTED_METHOD, effect.reason)
        assertEquals("DELETE", model.effectText(effect)); assertFalse(model.state.value.converting)
    }
    @Test fun queuedCopyUsesCapturedOutputEvenAfterAnotherConversionAndConsumeDoesNotReplay() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); runCurrent()
        model.copy(); runCurrent(); val effect = model.state.value.effects.single()
        model.convert(); runCurrent(); assertEquals("converted", model.state.value.draft.output)
        assertEquals("output", model.effectText(effect)); model.consume(effect)
        val restored = model(repo, savedCopy(saved)); runCurrent(); assertTrue(restored.state.value.effects.isEmpty())
    }
    @Test fun insertIsSingleFlightAndRetainedCallbackCanCompleteAfterRotationOrRetryFailure() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo); runCurrent(); model.insert(); model.insert(); runCurrent()
        val effect = model.state.value.effects.single(); assertTrue(model.state.value.inserting)
        model.consume(effect); model.insertionResult(effect.id, false); runCurrent()
        assertFalse(model.state.value.inserting); assertEquals(CurlNotice.InsertFailed, model.state.value.effects.single().notice)
        model.consume(model.state.value.effects.single()); model.insert(); runCurrent()
        val retry = model.state.value.effects.single(); model.consume(retry); model.insertionResult(effect.id, true); runCurrent()
        assertFalse(model.state.value.finished); model.insertionResult(retry.id, true); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun processRestoreAfterDeliveredInsertDoesNotInvokeExternalInsertionAgain() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); runCurrent()
        first.insert(); runCurrent(); first.consume(first.state.value.effects.single())
        val restored = model(repo, savedCopy(saved)); runCurrent()
        assertFalse(restored.state.value.inserting); assertEquals(CurlNotice.InsertFailed, restored.state.value.effects.single().notice)
        assertTrue(restored.state.value.effects.none { it.action == CurlAction.Insert })
    }
    @Test fun readFailureBlocksEditsAndConversionAndRetryPreservesOriginalDraft() = runTest(dispatcher) {
        val repo = Fake().apply { failRead = true }; val model = model(repo); runCurrent()
        assertTrue(model.state.value.loadFailed); model.input("bad overwrite", 0, 0); model.convert(); model.insert(); runCurrent()
        assertEquals(0, repo.writes.size); assertEquals(0, repo.conversions)
        repo.failRead = false; model.retryLoad(); runCurrent(); assertFalse(model.state.value.loadFailed)
        assertEquals(repo.initial.input, model.state.value.draft.input); assertEquals(repo.initial.output, model.state.value.draft.output)
    }
    @Test fun closeDuringConversionCancelsItsWorkAndCannotQueueLateEffects() = runTest(dispatcher) {
        val repo = Fake().apply { conversionGate = CompletableDeferred() }; val model = model(repo); runCurrent()
        val store = ViewModelStore().apply { put("model", model) }
        try {
            model.convert(); runCurrent(); model.finish(); repo.conversionGate!!.complete(Unit); runCurrent()
            assertTrue(model.state.value.finished); assertTrue(model.state.value.effects.isEmpty()); assertEquals("output", model.state.value.draft.output)
        } finally { store.clear() }
    }
    @Test fun nonWritableModeCannotInsertButCanCopyAndRevisionAlwaysIncreases() = runTest(dispatcher) {
        val repo = Fake(); val model = model(repo, canInsert = false); runCurrent()
        model.insert(); runCurrent(); assertTrue(model.state.value.effects.isEmpty())
        model.input("one", 0, 1); runCurrent(); model.input("two", 2, 3); runCurrent(); model.convert(); runCurrent()
        assertEquals(repo.writes.filter { !it.first.contains("effect") }.map { it.second.revision }.sorted(),
            repo.writes.filter { !it.first.contains("effect") }.map { it.second.revision })
        model.copy(); runCurrent(); assertEquals(CurlAction.Copy, model.state.value.effects.single().action)
    }
    private class Fake : CurlConversionRepository {
        var initial = CurlConversionDraft("curl https://example.com", "output")
        val drafts = mutableMapOf<String, CurlConversionDraft>(); val writes = mutableListOf<Pair<String, CurlConversionDraft>>()
        var failRead = false; var conversions = 0; var conversionFailure = false; var conversionGate: CompletableDeferred<Unit>? = null
        override suspend fun restore(session: String, inputKey: String?): CurlConversionDraft { if (failRead) error("read"); return drafts[session] ?: if (inputKey == null) CurlConversionDraft() else initial }
        override suspend fun save(session: String, draft: CurlConversionDraft) { writes += session to draft; if ((drafts[session]?.revision ?: Long.MIN_VALUE) <= draft.revision) drafts[session] = draft }
        override suspend fun convert(input: String, direction: CurlDirection): String {
            conversions++; conversionGate?.let { withContext(NonCancellable) { it.await() } }
            if (conversionFailure) throw CurlAnalyzeUrlConverter.ConversionException(CurlAnalyzeUrlConverter.ErrorReason.UNSUPPORTED_METHOD, "DELETE")
            return "converted"
        }
    }
}
