package io.legado.app.ui.replace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaceManagementNativeTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ReplaceManagementViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val labels = ReplaceManagementLabels("Enabled", "Disabled", "No group")
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private class Repo : ReplaceManagementRepository {
        val rows = MutableStateFlow((1L..3L).map { ReplaceManagementRow(it, "Rule $it", "Rule $it", null, true, it.toInt()) })
        val exported = mutableListOf<List<Long>>(); val released = mutableListOf<String>(); val remembered = mutableListOf<String>()
        var exportGate: CompletableDeferred<Unit>? = null
        override fun rows(filter: ReplaceManagementFilter) = rows
        override fun groups() = MutableStateFlow(emptyList<String>())
        override suspend fun enabled(ids: List<Long>, value: Boolean) {}
        override suspend fun group(ids: List<Long>, value: String, add: Boolean) {}
        override suspend fun edge(ids: List<Long>, top: Boolean) {}
        override suspend fun move(id: Long, target: Long, after: Boolean) {}
        override suspend fun delete(ids: List<Long>) {}
        override suspend fun export(ids: List<Long>): ReplaceManagementExport {
            exported += ids; withContext(NonCancellable) { exportGate?.await() }; return ReplaceManagementExport("owned-${exported.size}.json")
        }
        override suspend fun releaseExport(path: String) { released += path }
        override suspend fun importHistory() = remembered.toList()
        override suspend fun rememberImport(value: String) { remembered.add(0, value) }
        override suspend fun forgetImport(value: String) { remembered.remove(value) }
        override suspend fun manual() = false
        override suspend fun manual(value: Boolean) {}
        override suspend fun refreshPipeline() {}
    }
    private class Sessions : ReplaceManagementSessionRepository {
        var value: ReplaceManagementCheckpoint? = null; var failure = false; var readGate: CompletableDeferred<Unit>? = null
        override suspend fun read(session: String): ReplaceManagementCheckpoint? { withContext(NonCancellable) { readGate?.await() }; return value }
        override suspend fun write(session: String, value: ReplaceManagementCheckpoint) { if (failure) error("Disk failed"); if (value.revision >= (this.value?.revision ?: -1)) this.value = value }
        override suspend fun release(session: String) { value = null }
    }
    private class Sharing : ReplaceManagementSharingRepository {
        override suspend fun feedback(url: String) = ReplaceManagementShareFeedback(url, "Summary", url.startsWith("https:"))
        override suspend fun passphrase(url: String) = "Replacement passphrase:$url"
    }
    private fun model(repo: Repo = Repo(), sessions: Sessions = Sessions(), saved: SavedStateHandle = SavedStateHandle()) =
        ReplaceManagementViewModel(repo, sessions, saved, Sharing()).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; gates.forEach { it.complete(Unit) }; runCurrent() }
    }
    private fun TestScope.start(model: ReplaceManagementViewModel) { model.bind(labels); runCurrent() }
    @Test fun exportUsesOnlyVisibleSelectionInDisplayedOrderAndEmptyExportStillAllowed() = test {
        val repo = Repo(); val model = model(repo); start(model)
        model.effect(ReplaceManagementAction.Share); runCurrent(); assertNull(model.state.value.pending)
        model.effect(ReplaceManagementAction.Export); runCurrent(); assertEquals(listOf(emptyList<Long>()), repo.exported)
        val nonce = model.state.value.pending!!.nonce; assertTrue(model.delivered(nonce)); model.returned(nonce, null); runCurrent()
        model.selected(1, true); model.selected(3, true); repo.rows.value = listOf(repo.rows.value[2], repo.rows.value[1]); runCurrent()
        model.effect(ReplaceManagementAction.Share); runCurrent(); assertEquals(listOf(3L), repo.exported.last())
        assertEquals("exportReplaceRule.json", model.native(model.state.value.pending!!.nonce)!!.export!!.name)
    }
    @Test fun largeImportDraftAndPendingPayloadRemainPrivateAndReceiptPreventsReplayAfterRestore() = test {
        val sessions = Sessions(); val saved = SavedStateHandle(); val model = model(sessions = sessions, saved = saved); start(model)
        model.dialog(ReplaceManagementDialog.ImportUrl); model.draft("https://example/" + "Q".repeat(2000000)); model.confirmDialog(); runCurrent()
        val nonce = model.state.value.pending!!.nonce; assertEquals(2000016, model.native(nonce)!!.input!!.length)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
        val beforeAck = sessions.value!!; model.delivered(nonce); assertFalse(model.delivered(nonce)); runCurrent(); model.stop()
        sessions.value = beforeAck // Disk can lag the SavedState receipt.
        val restored = model(sessions = sessions, saved = copy(saved)); start(restored); assertNull(restored.state.value.pending)
    }
    @Test fun pickerStaleAndDuplicateResultsAreRejectedAndEarlyRestoreResultBecomesOneImport() = test {
        val sessions = Sessions(); val saved = SavedStateHandle(); val model = model(sessions = sessions, saved = saved); start(model)
        model.effect(ReplaceManagementAction.ImportLocal); runCurrent(); val nonce = model.state.value.pending!!.nonce
        model.delivered(nonce); runCurrent(); model.stop()
        sessions.readGate = gate(); val restored = model(sessions = sessions, saved = copy(saved)); restored.bind(labels); runCurrent()
        restored.returned("stale", "Wrong"); restored.returned(nonce, "content://exact"); sessions.readGate!!.complete(Unit); runCurrent()
        val next = restored.state.value.pending!!; assertEquals(ReplaceManagementAction.ImportInput, next.action)
        assertEquals("content://exact", restored.native(next.nonce)!!.input); restored.returned(nonce, "Duplicate"); runCurrent()
        assertEquals(next, restored.state.value.pending); assertNull(restored.waiting(ReplaceManagementAction.ImportLocal))
    }
    @Test fun canceledQrClearsWaitingAndAllowsAnotherRequestWithoutReaderChange() = test {
        val model = model(); start(model); model.effect(ReplaceManagementAction.ImportQr); runCurrent()
        val nonce = model.state.value.pending!!.nonce; model.delivered(nonce); model.returned(nonce, null); runCurrent()
        assertFalse(model.state.value.waitingNative); assertFalse(model.state.value.changed)
        model.effect(ReplaceManagementAction.Help); runCurrent(); assertEquals(ReplaceManagementAction.Help, model.state.value.pending!!.action)
    }
    @Test fun exportFeedbackRestoreAndPassphraseCopyUseOriginalUrlEvenWhenDisplayedDraftChanges() = test {
        val sessions = Sessions(); val saved = SavedStateHandle(); val first = model(sessions = sessions, saved = saved); start(first)
        first.effect(ReplaceManagementAction.Export); runCurrent(); val nonce = first.state.value.pending!!.nonce
        first.delivered(nonce); first.returned(nonce, "https://original"); runCurrent(); first.draft("Edited display"); runCurrent(); first.stop()
        val restored = model(sessions = sessions, saved = copy(saved)); start(restored)
        assertEquals("Edited display", restored.state.value.draft); restored.copyFeedback(); runCurrent()
        var request = restored.native(restored.state.value.pending!!.nonce)!!; assertEquals("https://original", request.input)
        restored.delivered(request.effect.nonce); runCurrent(); restored.passphrase(); runCurrent()
        assertEquals(ReplaceManagementDialog.Passphrase, restored.state.value.dialog); restored.copyFeedback(); runCurrent()
        request = restored.native(restored.state.value.pending!!.nonce)!!; assertEquals("Replacement passphrase:https://original", request.input)
    }
    @Test fun failedPendingWritePublishesNothingAndRetryKeepsSameOwnedFileAndNonce() = test {
        val sessions = Sessions().apply { failure = true }; val repo = Repo(); val model = model(repo, sessions); start(model)
        model.effect(ReplaceManagementAction.Export); runCurrent(); assertNull(model.state.value.pending); assertNotNull(model.state.value.error)
        assertTrue(repo.released.isEmpty()); model.effect(ReplaceManagementAction.Export); runCurrent(); assertEquals(1, repo.exported.size); sessions.failure = false; model.retry(); runCurrent()
        assertEquals(1, repo.exported.size); val pending = model.state.value.pending!!; assertEquals(sessions.value!!.pending!!.nonce, pending.nonce)
    }
    @Test fun lateNonCooperativeExportAfterStopIsReleasedWithoutPublishing() = test {
        val repo = Repo().apply { exportGate = gate() }; val model = model(repo); start(model)
        model.effect(ReplaceManagementAction.Export); runCurrent(); model.stop(); repo.exportGate!!.complete(Unit)
        // Cleanup runs on real IO; await its completion without draining an unbounded ticker.
        withContext(Dispatchers.IO) { withTimeout(5000) { while (repo.released.isEmpty()) delay(1) } }
        assertNull(model.state.value.pending); assertEquals(listOf("owned-1.json"), repo.released)
    }
    @Test fun editHasOptimisticChangedResultAndRepeatedNativeActionWhilePendingDoesNotOverwrite() = test {
        val model = model(); start(model); model.effect(ReplaceManagementAction.Edit, 2); model.effect(ReplaceManagementAction.Add); runCurrent()
        assertTrue(model.state.value.changed); val pending = model.state.value.pending!!
        assertEquals(2L, model.native(pending.nonce)!!.ruleId); model.effect(ReplaceManagementAction.Add); runCurrent(); assertEquals(pending, model.state.value.pending)
    }
    @Test fun clearingOwnerReleasesEveryDeliveredExportAndFailedDraftRetryWithoutTouchingOtherFiles() = test {
        val repo = Repo(); val sessions = Sessions(); val model = model(repo, sessions); start(model)
        repeat(2) {
            model.effect(ReplaceManagementAction.Export); runCurrent(); val nonce = model.state.value.pending!!.nonce
            model.delivered(nonce); model.returned(nonce, null); runCurrent()
        }
        sessions.failure = true; model.effect(ReplaceManagementAction.Export); runCurrent(); assertNotNull(model.state.value.error)
        sessions.failure = false; model.retry(); runCurrent(); val third = model.state.value.pending!!.nonce
        model.delivered(third); model.returned(third, null); runCurrent()
        assertEquals(listOf("owned-1.json", "owned-2.json", "owned-3.json"), sessions.value!!.ownedExports)
        val store = ViewModelStore(); store.put("owner", model); store.clear()
        withContext(Dispatchers.IO) { withTimeout(5000) { while (repo.released.size != 3) delay(1) } }
        assertEquals(setOf("owned-1.json", "owned-2.json", "owned-3.json"), repo.released.toSet())
        assertEquals(3, repo.exported.size)
    }

}
