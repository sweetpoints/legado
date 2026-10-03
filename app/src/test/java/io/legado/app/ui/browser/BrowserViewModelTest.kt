package io.legado.app.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import io.legado.app.model.browser.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun request(refetch: Boolean = true, verify: Boolean = true) = BrowserRequest("url", "Title", "Source", "source", 1,
        html = "x".repeat(1200000), verificationEnabled = verify, refetchAfterSuccess = refetch, verificationKey = "verification")
    private fun page(request: BrowserRequest) = BrowserPage(request, "resolved", request.html, true, mapOf("X-Custom" to "full"), "UA", BrowserSource(1, "metadata"))
    private fun model(repo: Fake = Fake(), saved: SavedStateHandle = SavedStateHandle(), request: BrowserRequest = request()) = BrowserViewModel(repo, saved) { request }
    private fun own(model: BrowserViewModel) = ViewModelStore().apply { put("browser", model) }
    private suspend fun ready(model: BrowserViewModel) { model.state.first { !it.loading }; yield() }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    @Test fun fullHtmlAndHeadersStayPrivateAndRestorationReusesPreparedPageWithoutRepeatingPost() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); assertEquals(1, repo.prepared); assertEquals(1200000, vm.state.value.page!!.html!!.length)
            assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(vm.state.value.page, restored.state.value.page); assertEquals(1, repo.prepared) }
            finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun failedDurablePreparationRetainsResponseForExplicitRetryAndNeverRepeatsPost() = runTest(dispatcher) {
        val repo = Fake().apply { failWrites = true }; val vm = model(repo); val owner = own(vm)
        try { ready(vm); assertTrue(vm.state.value.loadFailed); assertNull(vm.state.value.page); assertEquals(1, repo.prepared)
            vm.verify(); vm.deleteSource(); runCurrent(); assertTrue(repo.actions.isEmpty())
            repo.failWrites = false; vm.retry(); vm.state.first { !it.loading && !it.loadFailed }
            assertEquals(1, repo.prepared); assertNotNull(repo.value!!.page)
        } finally { owner.clear() }
    }
    @Test fun interruptedUnpreparedRequestNeedsExplicitRetryInsteadOfAutomaticallyRepeatingUnknownPost() = runTest(dispatcher) {
        val req = request(); val repo = Fake(BrowserSession(req)); val saved = SavedStateHandle(mapOf("preparing" to true))
        val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); assertTrue(vm.state.value.loadFailed); assertEquals(0, repo.prepared)
            vm.retry(); vm.state.first { !it.loading && !it.loadFailed }; assertEquals(1, repo.prepared)
        } finally { owner.clear() }
    }
    @Test fun refetchVerificationPublishesOnlyDurableReceiptAndConsumesExactlyOnceAfterRestore() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); repo.gate = CompletableDeferred(); vm.verify(); runCurrent(); assertTrue(vm.state.value.busy); assertNull(vm.state.value.receipt)
            repo.gate!!.complete(Unit); val pending = vm.state.first { it.receipt != null }; assertEquals(1, repo.refetched)
            assertEquals(BrowserVerification("verified", "resolved"), repo.value!!.receipt!!.verification)
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(pending.receipt, restored.state.value.receipt); val result = restored.prepareReceipt(pending.receipt!!)
                assertEquals(BrowserReceiptKind.Verified, result.kind); assertTrue(restored.consumeReceipt(result.id)); assertFalse(restored.consumeReceipt(result.id))
                val third = model(repo, copy(saved)); val last = own(third)
                try { ready(third); assertNull(third.state.value.receipt); assertTrue(third.state.value.finished) } finally { last.clear() }
            } finally { other.clear() }
        } finally { repo.gate?.complete(Unit); owner.clear() }
    }
    @Test fun nativeCaptureRequestRestoresAndDuplicateLateJavascriptCallbacksCannotPublishTwice() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved, request(false)); val owner = own(vm)
        try { ready(vm); vm.verify(); val id = vm.state.value.capture!!; assertTrue(vm.state.value.busy); assertEquals(0, repo.refetched)
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals(id, restored.state.value.capture); assertTrue(restored.state.value.busy)
                restored.captured(id, "captured", "navigated"); restored.captured(id, "duplicate", "wrong")
                restored.state.first { it.receipt != null }; assertEquals(1, repo.captured)
                assertEquals(BrowserVerification("captured", "navigated"), restored.prepareReceipt(restored.state.value.receipt!!).verification)
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun cloudflareBlocksWindowCloseButSolvedChallengeVerifiesAndProgressClampsWithoutPersistingEveryFrame() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); val owner = own(vm)
        try { ready(vm); val writes = repo.writes; vm.progress(120); assertEquals(100, vm.state.value.progress); vm.progress(-1); assertEquals(0, vm.state.value.progress)
            runCurrent(); assertEquals(writes, repo.writes); vm.challenge(true); vm.windowClose(); runCurrent(); assertEquals(0, repo.refetched)
            vm.challenge(false); vm.state.first { it.receipt != null }; assertEquals(1, repo.refetched)
        } finally { owner.clear() }
    }
    @Test fun imageAndPickerRequestAreDurableWithoutSavingDataUrlAndFailureCannotRemoveNewerDirectory() = runTest(dispatcher) {
        val repo = Fake(); val saved = SavedStateHandle(); val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); val image = "data:image/png;base64," + "x".repeat(1200000); vm.image(image); vm.requestImageDirectory(true)
            val key = vm.state.value.imageRequest!!; assertEquals("old-folder", vm.imageDirectory()); assertEquals(image, repo.value!!.image)
            assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 }); assertTrue(vm.consumeImageRequest(key)); assertFalse(vm.consumeImageRequest(key))
            repo.imageFailure = true; repo.directory = "new-folder"; vm.saveImage("old-folder"); vm.state.first { it.receipt != null }
            val receipt = vm.prepareReceipt(vm.state.value.receipt!!); assertEquals(BrowserReceiptKind.ImageFailed, receipt.kind); assertEquals("new-folder", repo.directory)
            assertEquals(listOf("image:old-folder", "forget:old-folder"), repo.actions)
        } finally { owner.clear() }
    }
    @Test fun sourceOperationsKeepExactOriginTypeAndCloseOnlyAfterSuccessWhileFailedDeletionIsRetryable() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); val owner = own(vm)
        try { ready(vm); repo.sourceFailure = true; vm.deleteSource(); runCurrent(); assertNull(vm.state.value.receipt); assertFalse(vm.state.value.finished)
            assertNotNull(vm.state.value.error); repo.sourceFailure = false; vm.disableSource(); vm.state.first { it.receipt != null }
            assertEquals(listOf("delete:source:1", "disable:source:1"), repo.actions); assertEquals(BrowserReceiptKind.Close, vm.prepareReceipt(vm.state.value.receipt!!).kind)
        } finally { owner.clear() }
    }
    @Test fun stoppedOwnerSuppressesLateNonCooperativeErrorsAndReleasePreventsResultResurrection() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Fake().apply { refetchGate = gate }; val vm = model(repo); val owner = own(vm)
        try { ready(vm); vm.verify(); runCurrent(); vm.releaseOwnedSession(); gate.complete(Unit); runCurrent()
            assertTrue(repo.closed); assertNull(repo.value); assertNull(vm.state.value.receipt); assertNull(vm.state.value.error)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun diskRevisionAboveRebootedClockIsTheBaselineBeforeWritingNewTitleAndFullscreenRestores() = runTest(dispatcher) {
        val req = request(); val revision = System.nanoTime() + 100000000000L; val repo = Fake(BrowserSession(req, page(req), revision = revision))
        val saved = SavedStateHandle(); val vm = model(repo, saved); val owner = own(vm)
        try { ready(vm); vm.title("new title"); vm.fullscreen(true); vm.flush(); assertTrue(repo.value!!.revision > revision)
            val restored = model(repo, copy(saved)); val other = own(restored)
            try { ready(restored); assertEquals("new title", restored.state.value.title); assertTrue(restored.state.value.fullscreen) }
            finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun windowCloseRequestedDuringImageSaveRunsOnceAfterImageToastInsteadOfBeingDropped() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Fake().apply { imageGate = gate }; val vm = model(repo); val owner = own(vm)
        try { ready(vm); vm.image("data:image"); vm.saveImage("folder"); runCurrent(); vm.windowClose(); assertEquals(0, repo.refetched)
            gate.complete(Unit); val image = vm.state.first { it.receipt != null }; assertEquals(BrowserReceiptKind.ImageSaved, vm.prepareReceipt(image.receipt!!).kind)
            vm.consumeReceipt(image.receipt!!); val verified = vm.state.first { it.receipt != null }
            assertEquals(BrowserReceiptKind.Verified, vm.prepareReceipt(verified.receipt!!).kind); assertEquals(1, repo.refetched)
        } finally { gate.complete(Unit); owner.clear() }
    }
    @Test fun failedVerificationReceiptWriteRetriesSameResultWithoutRefetchOrEarlyClose() = runTest(dispatcher) {
        val repo = Fake(); val vm = model(repo); val owner = own(vm)
        try { ready(vm); repo.gate = CompletableDeferred(); vm.verify(); runCurrent()
            assertEquals(1, repo.refetched); assertTrue(vm.state.value.busy); assertNull(vm.state.value.receipt)
            repo.failWrites = true; repo.gate!!.complete(Unit); runCurrent()
            assertEquals(1, repo.refetched); assertNull(vm.state.value.receipt); assertTrue(vm.state.value.persistError); assertFalse(vm.state.value.finished)
            vm.verify(); vm.deleteSource(); runCurrent(); assertEquals(1, repo.refetched); assertTrue(repo.actions.isEmpty())
            repo.failWrites = false; vm.retry(); val pending = vm.state.first { it.receipt != null }
            assertEquals(1, repo.refetched); assertFalse(pending.persistError); assertNull(pending.error)
            assertEquals(BrowserVerification("verified", "resolved"), vm.prepareReceipt(pending.receipt!!).verification)
            assertTrue(vm.consumeReceipt(pending.receipt!!)); assertTrue(vm.state.value.finished)
        } finally { owner.clear() }
    }
    @Test fun failedImageSuccessReceiptCannotBecomeImageFailureAndQueuedCloseWaitsForSameReceiptRetryAndConsume() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val repo = Fake().apply { imageGate = gate }; val vm = model(repo); val owner = own(vm)
        try { ready(vm); vm.image("data:image"); vm.flush(); vm.saveImage("folder"); runCurrent(); vm.windowClose()
            repo.failWrites = true; gate.complete(Unit); runCurrent()
            assertTrue(vm.state.value.persistError); assertNull(vm.state.value.receipt); assertEquals(0, repo.refetched)
            assertEquals(listOf("image:folder"), repo.actions)
            repo.failWrites = false; vm.retry(); val pending = vm.state.first { it.receipt != null }
            assertEquals(BrowserReceiptKind.ImageSaved, vm.prepareReceipt(pending.receipt!!).kind)
            assertEquals(0, repo.refetched); assertEquals(listOf("image:folder"), repo.actions)
            vm.consumeReceipt(pending.receipt!!); val verified = vm.state.first { it.receipt != null }
            assertEquals(BrowserReceiptKind.Verified, vm.prepareReceipt(verified.receipt!!).kind); assertEquals(1, repo.refetched)
            assertEquals(listOf("image:folder"), repo.actions)
        } finally { gate.complete(Unit); owner.clear() }
    }
    private class Fake(var value: BrowserSession? = null) : BrowserRepository {
        var prepared = 0; var refetched = 0; var captured = 0; var writes = 0; var closed = false
        var failWrites = false; var sourceFailure = false; var imageFailure = false; var directory: String? = "old-folder"
        var imageGate: CompletableDeferred<Unit>? = null
        var gate: CompletableDeferred<Unit>? = null; var refetchGate: CompletableDeferred<Unit>? = null
        val actions = mutableListOf<String>()
        override suspend fun prepare(request: BrowserRequest): BrowserPage { prepared++; return BrowserPage(request, "resolved", request.html, true, mapOf("X-Custom" to "full"), "UA", BrowserSource(1, "metadata")) }
        override suspend fun refetch(page: BrowserPage): BrowserVerification { refetched++; refetchGate?.let { withContext(NonCancellable) { it.await(); error("late failure") } }; return BrowserVerification("verified", "resolved") }
        override suspend fun captured(htmlJson: String, url: String): BrowserVerification { captured++; return BrowserVerification(htmlJson, url) }
        override suspend fun saveImage(data: String, directory: String) { actions += "image:$directory"; imageGate?.await(); if (imageFailure) error("image failed") }
        override suspend fun imageDirectory(): String? = directory
        override suspend fun imageDirectory(value: String) { directory = value }
        override suspend fun forgetImageDirectory(expected: String) { actions += "forget:$expected"; if (directory == expected) directory = null }
        override suspend fun disableSource(origin: String, type: Int) { actions += "disable:$origin:$type"; if (sourceFailure) error("source failed") }
        override suspend fun deleteSource(origin: String, type: Int) { actions += "delete:$origin:$type"; if (sourceFailure) error("source failed") }
        override suspend fun webCookies(url: String): BrowserWebCookies? = null
        override suspend fun cookie(url: String, value: String?) { actions += "cookie:$url:$value" }
        override suspend fun read(session: String): BrowserSession? { if (closed) throw BrowserSessionClosedException(); return value }
        override suspend fun create(session: String, seed: BrowserSession): BrowserSession { if (closed) throw BrowserSessionClosedException(); return value ?: seed.also { value = it } }
        override suspend fun write(session: String, snapshot: BrowserSession) { gate?.await(); if (closed) throw BrowserSessionClosedException(); if (failWrites) error("write failed")
            writes++; if (snapshot.revision >= (value?.revision ?: 0)) value = snapshot }
        override suspend fun release(session: String) { closed = true; value = null }
    }
}
