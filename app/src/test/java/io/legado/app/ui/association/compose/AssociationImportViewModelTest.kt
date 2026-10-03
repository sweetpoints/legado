package io.legado.app.ui.association.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.association.AssociationBookPreview
import io.legado.app.data.association.AssociationFileInspection
import io.legado.app.data.association.AssociationFileRepository
import io.legado.app.data.association.AssociationHostKind
import io.legado.app.data.association.AssociationImportOperations
import io.legado.app.data.association.AssociationInput
import io.legado.app.data.association.AssociationInputKind
import io.legado.app.data.association.AssociationNativeKind
import io.legado.app.data.association.AssociationNativeReceipt
import io.legado.app.data.association.AssociationNativeResult
import io.legado.app.data.association.AssociationNativeResultRepository
import io.legado.app.data.association.AssociationOnlinePayload
import io.legado.app.data.association.AssociationOnlineRepository
import io.legado.app.data.association.AssociationOperation
import io.legado.app.data.association.AssociationOperationResult
import io.legado.app.data.association.AssociationPhase
import io.legado.app.data.association.AssociationSession
import io.legado.app.data.association.AssociationSessionRepository
import io.legado.app.data.association.AssociationStagingResult
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssociationImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val input =
        AssociationInput(
            AssociationHostKind.File,
            AssociationInputKind.View,
            listOf("content://provider/" + "large".repeat(100_000)),
        )
    private val preview =
        AssociationBookPreview("book", "file:///private/book.txt", "book.txt", "complete JSON")

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        Dispatchers.resetMain()
    }

    @Test
    fun launchKeepsOnlyUuidSavedAndRestoresPreviewWithoutReadingIncomingContentAgain() =
        runTest(dispatcher) {
            val saved = SavedStateHandle()
            val sessions = MemorySessions()
            val files =
                Files(
                    AssociationFileInspection(
                        staging = AssociationStagingResult(previews = listOf(preview))
                    )
                )
            val model = model(saved, sessions, files)
            try {
                model.start(input)
                model.start(input.copy(text = "ignored second intent"))
                runCurrent()
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), saved.keys())
                assertEquals(1, sessions.creations)
                assertEquals(input, sessions.current!!.input)
                assertEquals(AssociationPhase.Preview, model.state.value.session!!.phase)
                assertEquals(listOf("book"), model.state.value.session!!.selectedIds)
                assertFalse(model.state.value.busy)
            } finally {
                clear(model)
                runCurrent()
            }
            val restored =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    files,
                )
            try {
                runCurrent()
                assertEquals(listOf(preview), restored.state.value.session!!.previews)
                assertEquals(1, files.calls)
                assertEquals(1, sessions.creations)
            } finally {
                clear(restored)
                runCurrent()
            }
        }

    @Test
    fun unreadablePrivateRestoreDoesNotCreateOrOverwriteAnEmptyImport() =
        runTest(dispatcher) {
            val sessions =
                MemorySessions().apply {
                    readFailure = IllegalStateException("corrupt private draft")
                }
            val files =
                Files(
                    AssociationFileInspection(
                        importType = "bookSource",
                        source = "file:///private/source.json",
                    )
                )
            val saved =
                SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "owned-ticket"))
            val model = model(saved, sessions, files)
            try {
                runCurrent()
                assertNotNull(model.state.value.restoreError)
                assertEquals("owned-ticket", model.state.value.ticket)
                model.start(input)
                model.retryInspection()
                runCurrent()
                assertEquals(0, sessions.creations)
                assertEquals(0, sessions.writes)
                assertEquals(0, files.calls)
                assertEquals(
                    "owned-ticket",
                    saved.get<String>(AssociationImportViewModel.TICKET_KEY),
                )
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun stoppedNonCooperativeInspectionCannotPublishLateMetadataOrPersistItsResult() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val files =
                Files(
                        AssociationFileInspection(
                            importType = "bookSource",
                            source = "file:///late.json",
                        )
                    )
                    .apply { resultGate = gate }
            val sessions = MemorySessions()
            val model = model(SavedStateHandle(), sessions, files)
            try {
                model.start(input)
                runCurrent()
                assertTrue(model.state.value.busy)
                assertEquals(1, sessions.writes)
                clear(model)
                gate.complete(Unit)
                runCurrent()
                assertEquals(1, sessions.writes)
                assertEquals(AssociationPhase.Loading, sessions.current!!.phase)
                assertTrue(sessions.current!!.effects.isEmpty())
            } finally {
                gate.complete(Unit)
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun failureRetainsPrivateInputAndExplicitRetryPublishesImportDialogOnce() =
        runTest(dispatcher) {
            val sessions = MemorySessions()
            val files =
                Files(
                        AssociationFileInspection(
                            importType = "replaceRule",
                            source = "file:///rules.json",
                        )
                    )
                    .apply { failure = IllegalStateException("provider unavailable") }
            val model = model(SavedStateHandle(), sessions, files)
            try {
                model.start(input)
                runCurrent()
                assertEquals(AssociationPhase.Failed, model.state.value.session!!.phase)
                assertEquals(input, sessions.current!!.input)
                files.failure = null
                model.retryInspection()
                runCurrent()
                assertEquals("replaceRule", model.state.value.session!!.effects.single().type)
                assertEquals(2, files.calls)
                assertEquals(1, sessions.creations)
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun selectionDirectoryCancellationAndNativeReturnPersistBeforeRecreation() =
        runTest(dispatcher) {
            val sessions = MemorySessions()
            val files =
                Files(
                    AssociationFileInspection(
                        staging = AssociationStagingResult(previews = listOf(preview))
                    )
                )
            val model = model(SavedStateHandle(), sessions, files)
            try {
                model.start(input)
                runCurrent()
                model.updateSelection(emptySet())
                runCurrent()
                assertTrue(sessions.current!!.selectedIds.isEmpty())
                model.updateSelection(setOf(preview.id, "unknown"))
                runCurrent()
                assertEquals(listOf(preview.id), sessions.current!!.selectedIds)
                model.requestDirectory()
                runCurrent()
                assertEquals(AssociationPhase.Directory, sessions.current!!.phase)
                model.cancelDirectory()
                runCurrent()
                assertEquals(AssociationPhase.Preview, sessions.current!!.phase)
                model.chooseSystemDirectory()
                runCurrent()
                val pending = sessions.current!!.effects.single()
                assertNotNull(model.claimNative(pending))
                assertTrue(sessions.current!!.effects.isEmpty())
                model.returnNative(pending)
                assertEquals(listOf(pending), sessions.current!!.effects)
                assertNotNull(model.claimNative(pending))
                model.acknowledgeNative(pending)
                assertTrue(sessions.current!!.effects.isEmpty())
                assertTrue(sessions.current!!.claimedEffects.isEmpty())
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun restoredAcceptedBackupRequiresNewConfirmationWithoutRunningEngineAgain() =
        runTest(dispatcher) {
            val sessions =
                MemorySessions().apply {
                    current =
                        AssociationSession(
                            input,
                            phase = AssociationPhase.Preview,
                            operation =
                                AssociationOperation("accepted", 0, "backup", accepted = true),
                        )
                }
            var calls = 0
            val actions =
                object : AssociationImportOperations {
                    override suspend fun execute(
                        ticket: String,
                        session: AssociationSession,
                        operation: AssociationOperation,
                    ): AssociationOperationResult {
                        calls++
                        return AssociationOperationResult()
                    }
                }
            val model =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    Files(AssociationFileInspection()),
                    actions,
                )
            try {
                runCurrent()
                model.confirmOperation("backup")
                runCurrent()
                assertEquals(0, calls)
                assertEquals(AssociationPhase.Failed, sessions.current!!.phase)
                assertTrue(sessions.current!!.error!!.contains("uncertain"))
                assertEquals("accepted", sessions.current!!.operation!!.token)
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun successfulOperationPersistsOneNativeReceiptAndConsumedRestoreDoesNotReplay() =
        runTest(dispatcher) {
            val sessions = MemorySessions()
            var calls = 0
            val actions =
                object : AssociationImportOperations {
                    override suspend fun execute(
                        ticket: String,
                        session: AssociationSession,
                        operation: AssociationOperation,
                    ): AssociationOperationResult {
                        calls++
                        return AssociationOperationResult(bookJson = "large complete book")
                    }
                }
            val files =
                Files(
                    AssociationFileInspection(
                        staging = AssociationStagingResult(previews = listOf(preview))
                    )
                )
            val model = model(SavedStateHandle(), sessions, files, actions)
            try {
                model.start(input)
                runCurrent()
                model.confirmOperation("local-import", "file:///destination")
                model.confirmOperation("local-import", "ignored")
                runCurrent()
                assertEquals(1, calls)
                assertEquals(AssociationPhase.Finished, sessions.current!!.phase)
                assertEquals(null, sessions.current!!.operation)
                val pending = sessions.current!!.effects.single()
                assertEquals("large complete book", pending.payload)
                assertNotNull(model.claimNative(pending))
                model.acknowledgeNative(pending)
            } finally {
                clear(model)
                runCurrent()
            }
            val restored =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    files,
                    actions,
                )
            try {
                runCurrent()
                assertTrue(restored.state.value.session!!.effects.isEmpty())
                assertEquals(1, calls)
                assertEquals(1, files.calls)
            } finally {
                clear(restored)
                runCurrent()
            }
        }

    @Test
    fun storagePermissionPrecedesNonContentInspectionAndDuplicateResultsDoNotRestart() =
        runTest(dispatcher) {
            val sessions = MemorySessions()
            val files =
                Files(
                    AssociationFileInspection(
                        staging = AssociationStagingResult(previews = listOf(preview))
                    )
                )
            val model = model(SavedStateHandle(), sessions, files)
            try {
                model.start(input.copy(uris = listOf("file:///external/book.txt")))
                runCurrent()
                assertEquals(0, files.calls)
                val request = sessions.current!!.effects.single()
                assertNotNull(model.claimNative(request))
                model.permissionResult(request, true)
                runCurrent()
                assertEquals(1, files.calls)
                assertTrue(sessions.current!!.storagePermissionGranted)
                assertEquals(AssociationPhase.Preview, model.state.value.session!!.phase)
                model.permissionResult(request, true)
                runCurrent()
                assertEquals(1, files.calls)
                assertFalse(model.state.value.busy)
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun directoryResultBeforeLoadImportsOnceAndRestoredCompletedResultDoesNotReplay() =
        runTest(dispatcher) {
            val receipt =
                AssociationNativeReceipt("picker", 0, AssociationNativeKind.SelectDirectory)
            val sessions =
                MemorySessions().apply {
                    current =
                        AssociationSession(
                            input,
                            phase = AssociationPhase.Directory,
                            previews = listOf(preview),
                            selectedIds = listOf(preview.id),
                            choosingDirectory = true,
                            claimedEffects = listOf(receipt),
                        )
                    readGate = CompletableDeferred()
                }
            val results = MemoryNativeResults()
            var imports = 0
            val actions =
                object : AssociationImportOperations {
                    override suspend fun execute(
                        ticket: String,
                        session: AssociationSession,
                        operation: AssociationOperation,
                    ): AssociationOperationResult {
                        imports++
                        assertEquals("content://picked/folder", operation.payload)
                        assertEquals(listOf(preview.id), session.selectedIds)
                        return AssociationOperationResult()
                    }
                }
            val saved = SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket"))
            val model =
                model(
                    saved,
                    sessions,
                    Files(AssociationFileInspection(finished = true)),
                    actions,
                    results,
                )
            try {
                runCurrent()
                assertFalse(model.state.value.loaded)
                val result = AssociationNativeResult(receipt, directory = "content://picked/folder")
                results.record("ticket", result)
                sessions.readGate!!.complete(Unit)
                runCurrent()
                assertEquals(1, imports)
                assertEquals(AssociationPhase.Finished, model.state.value.session!!.phase)
                assertTrue(results.values.isEmpty())
                assertEquals(setOf(AssociationImportViewModel.TICKET_KEY), saved.keys())
                // A duplicate callback whose platform receipt was already accepted is ignored.
                model.recordNativeResult("ticket", result)
                runCurrent()
                assertEquals(1, imports)
            } finally {
                clear(model)
                runCurrent()
            }
            val restored =
                model(
                    saved,
                    sessions,
                    Files(AssociationFileInspection(finished = true)),
                    actions,
                    results,
                )
            try {
                runCurrent()
                assertEquals(1, imports)
                assertFalse(restored.state.value.nativeResultPending)
            } finally {
                clear(restored)
                runCurrent()
            }
        }

    @Test
    fun acknowledgedFolderWithoutAcceptedImportRecoversFromRetainedNativeResult() =
        runTest(dispatcher) {
            val receipt =
                AssociationNativeReceipt("picker", 0, AssociationNativeKind.SelectDirectory)
            val sessions =
                MemorySessions().apply {
                    current =
                        AssociationSession(
                            input,
                            phase = AssociationPhase.Preview,
                            previews = listOf(preview),
                            selectedIds = listOf(preview.id),
                        )
                }
            val results =
                MemoryNativeResults().apply {
                    values = listOf(AssociationNativeResult(receipt, directory = "file:///picked"))
                }
            var imports = 0
            val actions =
                object : AssociationImportOperations {
                    override suspend fun execute(
                        ticket: String,
                        session: AssociationSession,
                        operation: AssociationOperation,
                    ): AssociationOperationResult {
                        imports++
                        return AssociationOperationResult()
                    }
                }
            val model =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    Files(AssociationFileInspection(finished = true)),
                    actions,
                    results,
                )
            try {
                runCurrent()
                assertEquals(1, imports)
                assertTrue(results.values.isEmpty())
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun directoryCancellationBeforeRestoreReturnsPreviewWithoutImport() =
        runTest(dispatcher) {
            val receipt =
                AssociationNativeReceipt("picker", 0, AssociationNativeKind.SelectDirectory)
            val sessions =
                MemorySessions().apply {
                    current =
                        AssociationSession(
                            input,
                            phase = AssociationPhase.Directory,
                            previews = listOf(preview),
                            selectedIds = listOf(preview.id),
                            choosingDirectory = true,
                            claimedEffects = listOf(receipt),
                        )
                }
            val results =
                MemoryNativeResults().apply { values = listOf(AssociationNativeResult(receipt)) }
            val model =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    Files(AssociationFileInspection(finished = true)),
                    nativeResults = results,
                )
            try {
                runCurrent()
                assertEquals(AssociationPhase.Preview, model.state.value.session!!.phase)
                assertFalse(model.state.value.session!!.choosingDirectory)
                assertTrue(results.values.isEmpty())
            } finally {
                clear(model)
                runCurrent()
            }
        }

    private class MemoryNativeResults : AssociationNativeResultRepository {
        var values = emptyList<AssociationNativeResult>()

        override suspend fun record(ticket: String, result: AssociationNativeResult): Boolean {
            if (values.any { it.receipt == result.receipt }) return false
            values = values + result
            return true
        }

        override suspend fun read(ticket: String) = values

        override suspend fun acknowledge(ticket: String, receipt: AssociationNativeReceipt) {
            values = values.filterNot { it.receipt == receipt }
        }
    }

    @Test
    fun selectionProofRejectsFailedPersistenceAndBusyInitialInput() =
        runTest(dispatcher) {
            val sessions =
                MemorySessions().apply {
                    current =
                        AssociationSession(
                            input,
                            phase = AssociationPhase.Preview,
                            previews = listOf(preview),
                            selectedIds = listOf(preview.id),
                        )
                    readGate = CompletableDeferred()
                }
            val model =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    Files(AssociationFileInspection(finished = true)),
                )
            try {
                runCurrent()
                assertFalse(model.updateSelectionWithProof(emptySet()).await())
                sessions.readGate!!.complete(Unit)
                runCurrent()
                sessions.writeFailure = IllegalStateException("Atomic write failed")
                val proof = model.updateSelectionWithProof(emptySet())
                runCurrent()
                assertFalse(proof.await())
                assertEquals(listOf(preview.id), sessions.current!!.selectedIds)
                assertEquals("Atomic write failed", model.state.value.restoreError)
                assertFalse(model.state.value.busy)
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun callbackRequiresLoadedLiveOwnerAndRejectsCloseWithoutStoreClear() =
        runTest(dispatcher) {
            val sessions =
                MemorySessions().apply {
                    current = AssociationSession(input, phase = AssociationPhase.Preview)
                    readGate = CompletableDeferred()
                }
            val model =
                model(
                    SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket")),
                    sessions,
                    Files(AssociationFileInspection(finished = true)),
                )
            try {
                runCurrent()
                assertFalse(model.acceptsCallback(null, null))
                assertFalse(model.acceptsCallback("ticket", 0))
                sessions.readGate!!.complete(Unit)
                runCurrent()
                assertTrue(model.acceptsCallback("ticket", 0))
                model.closeOwnedSession()
                assertFalse(model.acceptsCallback("ticket", 0))
            } finally {
                clear(model)
                runCurrent()
            }
        }

    @Test
    fun completedImportRetainsFinalMessageAndRejectsSecondConfirmation() =
        runTest(dispatcher) {
            val sessions =
                MemorySessions().apply {
                    current = AssociationSession(input, phase = AssociationPhase.ReadConfig)
                }
            var imports = 0
            val actions =
                object : AssociationImportOperations {
                    override suspend fun execute(
                        ticket: String,
                        session: AssociationSession,
                        operation: AssociationOperation,
                    ): AssociationOperationResult {
                        imports++
                        return AssociationOperationResult(message = "Imported configuration")
                    }
                }
            val saved = SavedStateHandle(mapOf(AssociationImportViewModel.TICKET_KEY to "ticket"))
            val model =
                model(saved, sessions, Files(AssociationFileInspection(finished = true)), actions)
            try {
                runCurrent()
                model.confirmOperation("read-config")
                runCurrent()
                assertEquals("Imported configuration", sessions.current!!.completionMessage)
                model.confirmOperation("read-config")
                runCurrent()
                assertEquals(1, imports)
            } finally {
                clear(model)
                runCurrent()
            }
            val restored =
                model(saved, sessions, Files(AssociationFileInspection(finished = true)), actions)
            try {
                runCurrent()
                assertEquals(
                    "Imported configuration",
                    restored.state.value.session!!.completionMessage,
                )
                assertEquals(1, imports)
            } finally {
                clear(restored)
                runCurrent()
            }
        }

    private fun model(
        saved: SavedStateHandle,
        sessions: MemorySessions,
        files: Files,
        actions: AssociationImportOperations? = null,
        nativeResults: AssociationNativeResultRepository? = null,
    ) =
        AssociationImportViewModel(
            saved,
            sessions,
            files,
            object : AssociationOnlineRepository {
                override suspend fun determine(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Unused")

                override suspend fun readConfig(
                    ticket: String,
                    url: String,
                ): AssociationOnlinePayload = error("Unused")

                override suspend fun text(url: String): String = error("Unused")
            },
            actions,
            nativeResults,
        )

    private fun clear(model: AssociationImportViewModel) {
        ViewModelStore().apply {
            put("model", model)
            clear()
        }
    }

    private class Files(private val result: AssociationFileInspection) : AssociationFileRepository {
        var calls = 0
        var failure: Throwable? = null
        var resultGate: CompletableDeferred<Unit>? = null

        override suspend fun inspect(
            ticket: String,
            input: AssociationInput,
        ): AssociationFileInspection {
            calls++
            resultGate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return result
        }
    }

    private class MemorySessions : AssociationSessionRepository {
        var current: AssociationSession? = null
        var creations = 0
        var writes = 0
        var writeFailure: Throwable? = null
        var readFailure: Throwable? = null
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun create(input: AssociationInput): String {
            creations++
            current = AssociationSession(input)
            return "ticket"
        }

        override suspend fun read(ticket: String): AssociationSession {
            readGate?.await()
            readFailure?.let { throw it }
            return checkNotNull(current)
        }

        override suspend fun write(ticket: String, value: AssociationSession): Boolean {
            writeFailure?.let { throw it }
            if (value.revision <= checkNotNull(current).revision) return false
            writes++
            current = value
            return true
        }

        override suspend fun writeBytes(ticket: String, name: String, bytes: ByteArray): Unit =
            error("Unused")

        override suspend fun readBytes(ticket: String, name: String): ByteArray = error("Unused")

        override suspend fun release(ticket: String) {
            current = null
        }
    }
}
