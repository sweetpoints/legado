package io.legado.app.ui.file

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.HandleFileCheckpoint
import io.legado.app.data.repository.HandleFileChoicesRepository
import io.legado.app.data.repository.HandleFileChoicesSessionRepository
import io.legado.app.data.repository.HandleFileInput
import io.legado.app.data.repository.HandleFileIssue
import io.legado.app.data.repository.HandleFileIssueException
import io.legado.app.data.repository.HandleFilePending
import io.legado.app.data.repository.HandleFileSeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HandleFileChoicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<HandleFileChoicesViewModel>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    private fun model(
        saved: SavedStateHandle = SavedStateHandle(),
        repo: Files = Files(),
        disk: Disk = Disk(),
    ) =
        HandleFileChoicesViewModel(saved, repo, disk).also {
            models += it
        }

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach {
                    it.stop()
                }
                runCurrent()
            }
        }

    @Test
    fun acceptedFinishedResultRestoresAndDeferredClaimCanRetryWithoutTransport() = test {
        val disk =
            Disk().apply {
                input = HandleFileInput(value = "Caller value")
                value =
                    HandleFileCheckpoint(4, "Result", result = "https://accepted", finished = true)
            }
        val repository = Files()
        val restored = model(repo = repository, disk = disk)
        restored.load()
        runCurrent()
        assertTrue(restored.resultDelivered())
        assertFalse(restored.resultDelivered())
        restored.deferResultDelivery()
        assertTrue(restored.resultDelivered())
        assertEquals("https://accepted", restored.state.value.result)
        assertEquals(0, repository.uploads)
        assertEquals(0, repository.saves)
    }

    @Test
    fun failedNativeLaunchRetryOwnsNewNonceAndRejectsOldCallback() = test {
        val disk = Disk()
        val model = model(disk = disk)
        model.load(HandleFileSeed(HandleFileInput(mode = 1)))
        runCurrent()
        model.choose(1)
        runCurrent()
        val originalNonce = model.state.value.pending!!.nonce
        assertTrue(model.nativeDelivered(originalNonce))
        model.nativeFailed(originalNonce, IllegalStateException("Picker unavailable"))
        model.retry()
        runCurrent()
        val retryNonce = model.state.value.pending!!.nonce
        assertNotEquals(originalNonce, retryNonce)
        assertFalse(model.state.value.pending!!.delivered)
        model.returned(originalNonce, "content://stale")
        runCurrent()
        assertNull(model.state.value.result)
        model.returned(retryNonce, "content://current")
        runCurrent()
        assertEquals("content://current", model.state.value.result)
    }

    @Test
    fun initialStageFailureStillAllowsCallerCancellation() = test {
        val disk = Disk().apply { failStage = true }
        val model = model(disk = disk)
        model.load(HandleFileSeed(HandleFileInput()))
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertNotNull(model.state.value.error)
        model.close()
        assertTrue(model.state.value.finished)
        assertNull(disk.value)
    }

    @Test
    fun registryResultDuringBusyClaimIsBufferedWithoutDuplicateOrStaleReplacement() = test {
        val disk = Disk()
        val model = model(disk = disk)
        model.load(HandleFileSeed(HandleFileInput(mode = 1)))
        runCurrent()
        model.choose(1)
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        disk.writeGate = CompletableDeferred()
        val claiming = launch { model.nativeDelivered(nonce) }
        runCurrent()
        assertTrue(model.state.value.busy)
        model.returned(nonce, "content://first")
        model.returned(nonce, "content://duplicate")
        model.returned("stale", "content://stale")
        disk.writeGate!!.complete(Unit)
        claiming.join()
        runCurrent()
        assertEquals("content://first", model.state.value.result)
        assertEquals("content://first", disk.value!!.result)
    }

    @Test
    fun canceledMimePreparationLeavesNativeReceiptUnclaimed() = test {
        val repository = Files().apply { mimeGate = CompletableDeferred() }
        val disk = Disk()
        val model = model(repo = repository, disk = disk)
        model.load(HandleFileSeed(HandleFileInput(mode = 1)))
        runCurrent()
        model.choose(1)
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        var prepared: HandleFileNativeRequest? = null
        val preparing = launch { prepared = model.prepareNative(nonce) }
        runCurrent()
        preparing.cancel()
        repository.mimeGate!!.complete(Unit)
        preparing.join()
        assertNull(prepared)
        assertFalse(disk.value!!.pending!!.delivered)
        assertNotNull(model.prepareNative(nonce))
    }

    @Test
    fun canceledDurableClaimRollsBackLatestDiskRevisionAndCanResume() = test {
        val disk = Disk()
        val model = model(disk = disk)
        model.load(HandleFileSeed(HandleFileInput(mode = 1)))
        runCurrent()
        model.choose(1)
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        disk.writeGate = CompletableDeferred()
        disk.protectWrites = true
        val claiming = launch {
            try {
                model.nativeDelivered(nonce)
            } finally {
                model.nativeDeferred(nonce)
            }
        }
        runCurrent()
        claiming.cancel()
        disk.writeGate!!.complete(Unit)
        claiming.join()
        runCurrent()
        assertFalse(disk.value!!.pending!!.delivered)
        assertFalse(model.state.value.pending!!.delivered)
        assertFalse(model.state.value.busy)
        assertTrue(model.nativeDelivered(nonce))
    }

    private class Files : HandleFileChoicesRepository {

        var uploads = 0
        var saves = 0
        var mimeGate: CompletableDeferred<Unit>? = null
        var manualIssue: HandleFileIssue? = null
        var acceptedGate: CompletableDeferred<Unit>? = null
        val uploadedFileNames = mutableListOf<String>()

        override suspend fun mimeTypes(extensions: List<String>): List<String> {
            mimeGate?.let { withContext(NonCancellable) { it.await() } }
            return listOf("*/*")
        }

        override suspend fun manual(text: String, image: Boolean): String {

            manualIssue?.let {
                throw HandleFileIssueException(it)
            }
            return "file://$text"
        }

        override suspend fun save(directory: String, name: String, bytes: ByteArray): String {
            ++saves
            return "$directory/$name"
        }

        override suspend fun upload(name: String, bytes: ByteArray, contentType: String): String {
            ++uploads
            return "https://accepted"
        }

        override suspend fun uploadRecorded(
            name: String,
            bytes: ByteArray,
            contentType: String,
            receipt: suspend (String) -> Unit,
        ): String {
            val result = upload(name, bytes, contentType)
            return withContext(NonCancellable) {
                receipt(result)
                acceptedGate?.await()
                result
            }
        }

        override suspend fun uploadFileRecorded(
            name: String,
            sourceFileName: String,
            bytes: ByteArray,
            contentType: String,
            receipt: suspend (String) -> Unit,
        ): String {
            uploadedFileNames += sourceFileName
            return uploadRecorded(name, bytes, contentType, receipt)
        }
    }

    private class Disk : HandleFileChoicesSessionRepository {

        var input: HandleFileInput? = null
        var value: HandleFileCheckpoint? = null
        var failStage = false
        var failResult = false
        var writeGate: CompletableDeferred<Unit>? = null
        var protectWrites = false
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun stage(id: String, seed: HandleFileSeed): HandleFileInput {

            if (failStage) error("Initial write failed")
            return seed.input.also {
                input = it
            }
        }

        override suspend fun input(id: String): HandleFileInput? {
            readGate?.let {
                withContext(NonCancellable) {
                    it.await()
                }
            }
            return input
        }

        override suspend fun bytes(id: String) = "Exact".toByteArray()

        override suspend fun read(id: String) = value

        override suspend fun write(id: String, value: HandleFileCheckpoint) {
            if (protectWrites) withContext(NonCancellable) { writeGate?.await() }
            else writeGate?.await()

            if (failResult && value.phase == "Result") error("Receipt failed")
            if ((this.value?.revision ?: -1) <= value.revision) this.value = value
        }

        override suspend fun release(id: String) {
            input = null
            value = null
        }
    }

    @Test
    fun failedInitialSeedIsRetainedForSameSessionRetry() = test {
        val disk =
            Disk().apply {
                failStage = true
            }
        val saved = SavedStateHandle()
        val vm = model(saved, disk = disk)
        vm.load(HandleFileSeed(HandleFileInput(3, "Exact title"), "Payload"))
        runCurrent()
        assertFalse(vm.state.value.loaded)
        disk.failStage = false
        vm.retry()
        runCurrent()
        assertEquals("Exact title", vm.state.value.input!!.title)
        assertEquals(
            1,
            saved.keys().count {
                it == "handleFile.session"
            },
        )
    }

    @Test
    fun nativeReceiptAndLateOldNonceDoNotConsumeNewOwner() = test {
        val disk = Disk()
        val vm = model(disk = disk)
        vm.load(HandleFileSeed(HandleFileInput(1)))
        runCurrent()
        vm.choose(1)
        runCurrent()
        val old = vm.state.value.pending!!.nonce
        assertTrue(vm.nativeDelivered(old))
        assertFalse(vm.nativeDelivered(old))
        vm.fallback(old)
        runCurrent()
        val next = vm.state.value.pending!!.nonce
        assertNotEquals(old, next)
        vm.returned(old, "file://Wrong")
        runCurrent()
        assertNull(vm.state.value.result)
        vm.returned(next, "file://Correct")
        runCurrent()
        assertEquals("file://Correct", vm.state.value.result)
    }

    @Test
    fun earlyCallbackWaitsForOwnedLoadedCheckpointAndDuplicateResultIsIgnored() = test {
        val disk =
            Disk().apply {
                input = HandleFileInput(1)
                value =
                    HandleFileCheckpoint(4, "Native", pending = HandleFilePending(1, "nonce", true))
            }
        val vm = model(disk = disk)
        vm.returned("nonce", "content://exact")
        vm.load()
        runCurrent()
        assertEquals("content://exact", vm.state.value.result)
        vm.returned("nonce", "Wrong")
        runCurrent()
        assertEquals("content://exact", vm.state.value.result)
        assertTrue(vm.resultDelivered())
        assertFalse(vm.resultDelivered())
        assertTrue(disk.value!!.finished)
    }

    @Test
    fun fullManualDraftAndSelectionAreDiskOwnedWithSmallSavedState() = test {
        val saved = SavedStateHandle()
        val disk = Disk()
        val vm = model(saved, disk = disk)
        vm.load(HandleFileSeed(HandleFileInput(4)))
        runCurrent()
        vm.choose(113)
        runCurrent()
        vm.manualReady(vm.state.value.pending!!.nonce)
        runCurrent()
        val text = "Large".repeat(400000)
        vm.text(text, 7, 11)
        runCurrent()
        assertEquals(text, disk.value!!.draft)
        assertEquals(setOf("handleFile.session", "handleFile.revision"), saved.keys())
        val restored =
            model(
                SavedStateHandle(
                    saved.keys().associateWith {
                        saved.get<Any>(it)
                    }
                ),
                disk = disk,
            )
        restored.load()
        runCurrent()
        assertEquals(text, restored.state.value.draft)
        assertEquals(11, restored.state.value.end)
    }

    @Test
    fun uploadSuccessReceiptFailureRetriesOnlyReceiptAndNeverTransport() = test {
        val disk =
            Disk().apply {
                failResult = true
            }
        val files = Files()
        val vm = model(repo = files, disk = disk)
        vm.load(
            HandleFileSeed(
                HandleFileInput(3, fileName = "rule.json", contentType = "application/json"),
                "Exact",
            )
        )
        runCurrent()
        vm.choose(111)
        runCurrent()
        assertEquals(1, files.uploads)
        assertNotNull(vm.state.value.error)
        disk.failResult = false
        vm.retry()
        runCurrent()
        assertEquals(1, files.uploads)
        assertEquals("https://accepted", vm.state.value.result)
        assertEquals(
            111,
            vm.state.value.pending!!.action,
        ) // Upload result intentionally excludes original value in host.
    }

    @Test
    fun exportSaveSuccessRestoresFixedResultWithoutRewritingFile() = test {
        val disk = Disk()
        val files = Files()
        val saved = SavedStateHandle()
        val vm = model(saved, files, disk)
        vm.load(
            HandleFileSeed(
                HandleFileInput(3, fileName = "rule.json", contentType = "application/json"),
                "Exact",
            )
        )
        runCurrent()
        vm.choose(0)
        runCurrent()
        vm.returned(vm.state.value.pending!!.nonce, "content://directory")
        runCurrent()
        assertEquals(1, files.saves)
        val restored =
            model(
                SavedStateHandle(
                    saved.keys().associateWith {
                        saved.get<Any>(it)
                    }
                ),
                files,
                disk,
            )
        restored.load()
        runCurrent()
        assertEquals("content://directory/rule.json", restored.state.value.result)
        restored.retry()
        runCurrent()
        assertEquals(1, files.saves)
    }

    @Test
    fun nonCooperativeLoadAfterStopNeverPublishesAndUnloadedInteractionsAreIgnored() = test {
        val gate = CompletableDeferred<Unit>()
        val disk =
            Disk().apply {
                input = HandleFileInput(1)
                readGate = gate
            }
        val vm = model(disk = disk)
        vm.load()
        runCurrent()
        vm.choose(1)
        vm.close()
        assertNull(vm.state.value.pending)
        vm.stop()
        gate.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.loaded)
    }

    @Test
    fun cancelAndFinishedRestoreNeverWriteOrLaunchNativeAgain() = test {
        val disk = Disk()
        val saved = SavedStateHandle()
        val vm = model(saved, disk = disk)
        vm.load(HandleFileSeed(HandleFileInput(1)))
        runCurrent()
        vm.close()
        runCurrent()
        assertTrue(vm.state.value.finished)
        val restored =
            model(
                SavedStateHandle(
                    saved.keys().associateWith {
                        saved.get<Any>(it)
                    }
                ),
                disk = disk,
            )
        restored.load()
        runCurrent()
        restored.choose(1)
        assertTrue(restored.state.value.finished)
        assertNull(restored.state.value.pending)
    }

    @Test
    fun typedManualValidationKeepsOriginalToastAndDismissBehavior() = test {
        val disk = Disk()
        val files =
            Files().apply {
                manualIssue = HandleFileIssue.InvalidDirectory
            }
        val vm = model(repo = files, disk = disk)
        vm.load(HandleFileSeed(HandleFileInput(0)))
        runCurrent()
        vm.choose(112)
        runCurrent()
        vm.manualReady(vm.state.value.pending!!.nonce)
        runCurrent()
        vm.text("Exact path", 2, 4)
        runCurrent()
        vm.confirmManual()
        runCurrent()
        assertEquals(HandleFileIssue.InvalidDirectory, vm.state.value.issue)
        assertTrue(vm.state.value.finished)
        assertNull(vm.state.value.result)
        assertEquals("Exact path", disk.value!!.draft)
    }

    @Test
    fun acceptedUploadReturningAfterStopIsNotPublishedAndRestoresWithoutRetransmission() = test {
        val gate = CompletableDeferred<Unit>()
        val files = Files().apply { acceptedGate = gate }
        val disk = Disk()
        val saved = SavedStateHandle()
        val original = model(saved, files, disk)
        original.load(
            HandleFileSeed(
                HandleFileInput(3, fileName = "rule.json", contentType = "application/json"),
                "Exact",
            )
        )
        runCurrent()
        original.choose(111)
        runCurrent()
        assertEquals("Result", disk.value!!.phase)
        original.stop()
        gate.complete(Unit)
        runCurrent()
        assertNull(original.state.value.result)
        assertEquals("Uploading", original.state.value.phase)

        val restoredSaved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val restored = model(restoredSaved, files, disk)
        restored.load()
        runCurrent()
        assertEquals("https://accepted", restored.state.value.result)
        assertEquals(1, files.uploads)
    }

    @Test
    fun originalFileBasenameSelectsFileUploadBranchAfterPrivateInputRestore() = test {
        val files = Files()
        val disk =
            Disk().apply {
                input =
                    HandleFileInput(
                        mode = 3,
                        fileName = "display-rule.json",
                        contentType = "application/json",
                        sourceFileName = "original-source.json",
                    )
            }
        val restored = model(repo = files, disk = disk)
        restored.load()
        runCurrent()
        restored.choose(111)
        runCurrent()
        assertEquals(listOf("original-source.json"), files.uploadedFileNames)
        assertEquals("https://accepted", restored.state.value.result)
        assertEquals(1, files.uploads)
    }

    @Test
    fun latePermissionCompletionCannotReopenManualEditorAfterAcceptedResult() = test {
        val disk = Disk()
        val viewModel = model(disk = disk)
        viewModel.load(HandleFileSeed(HandleFileInput(4)))
        runCurrent()
        viewModel.choose(113)
        runCurrent()
        val nonce = viewModel.state.value.pending!!.nonce
        viewModel.manualReady(nonce)
        runCurrent()
        viewModel.text("Exact image", 3, 3)
        runCurrent()
        viewModel.confirmManual()
        runCurrent()
        val accepted = disk.value

        viewModel.manualReady(nonce)
        runCurrent()
        assertEquals("Result", viewModel.state.value.phase)
        assertEquals("file://Exact image", viewModel.state.value.result)
        assertEquals(accepted, disk.value)
    }

    @Test
    fun latePickerLaunchFailureCannotReplacePendingOwnerAfterAcceptedResult() = test {
        val disk = Disk()
        val viewModel = model(disk = disk)
        viewModel.load(HandleFileSeed(HandleFileInput(1)))
        runCurrent()
        viewModel.choose(1)
        runCurrent()
        val nonce = viewModel.state.value.pending!!.nonce
        viewModel.returned(nonce, "content://accepted")
        runCurrent()
        val accepted = disk.value

        viewModel.fallback(nonce)
        runCurrent()
        assertEquals("Result", viewModel.state.value.phase)
        assertEquals("content://accepted", viewModel.state.value.result)
        assertEquals(nonce, viewModel.state.value.pending!!.nonce)
        assertEquals(accepted, disk.value)
    }
}
