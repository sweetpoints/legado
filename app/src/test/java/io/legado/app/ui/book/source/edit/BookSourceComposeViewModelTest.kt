package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.BookSource
import io.legado.app.model.sourceEngine.SourceMigrationIssue
import io.legado.app.model.sourceEngine.SourceMigrationPreview
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookSourceComposeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test
    fun queuedFieldInputPersistsLatestValueAndRestoresOnlyUuid() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val model = BookSourceComposeViewModel(repository, savedState, "old")
            runCurrent()
            model.updateField(0, "bookSourceName", "one", 1, 2)
            model.updateField(0, "bookSourceName", "two", 3, 0)
            runCurrent()
            val restored =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(
                        mapOf("bookSourceDraftId" to savedState.get<String>("bookSourceDraftId"))
                    ),
                    "old",
                )
            runCurrent()
            assertEquals(
                "two",
                restored.state.value.document!!.form.field(0, "bookSourceName")!!.value,
            )
            assertEquals(
                3,
                restored.state.value.document!!.form.field(0, "bookSourceName")!!.selectionStart,
            )
            assertEquals(setOf("bookSourceDraftId"), savedState.keys())
        }

    @Test
    fun undoRedoIsFieldSpecificAndPersistsAcrossRestoration() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val model = BookSourceComposeViewModel(repository, savedState, "old")
            runCurrent()
            model.focus(1, "bookList")
            model.updateField(1, "bookList", "first", 5, 5)
            model.updateField(2, "bookList", "other tab", 9, 9)
            model.updateField(1, "bookList", "second", 6, 6)
            model.undo()
            runCurrent()
            assertEquals("first", model.state.value.document!!.form.field(1, "bookList")!!.value)
            assertEquals(
                "other tab",
                model.state.value.document!!.form.field(2, "bookList")!!.value,
            )
            val restored =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(
                        mapOf("bookSourceDraftId" to savedState.get<String>("bookSourceDraftId"))
                    ),
                    "old",
                )
            runCurrent()
            restored.undo(redo = true)
            runCurrent()
            assertEquals(
                "second",
                restored.state.value.document!!.form.field(1, "bookList")!!.value,
            )
        }

    @Test
    fun keyboardInsertionReplacesRawReversedSelectionAndSupportsUndo() =
        runTest(dispatcher) {
            val model = BookSourceComposeViewModel(FakeRepository(), SavedStateHandle(), "old")
            runCurrent()
            model.focus(1, "bookList")
            model.updateField(1, "bookList", "A\r\n中😀Z", 6, 3)
            model.insert("x")
            assertEquals("A\r\nxZ", model.state.value.document!!.form.field(1, "bookList")!!.value)
            model.undo()
            assertEquals(
                "A\r\n中😀Z",
                model.state.value.document!!.form.field(1, "bookList")!!.value,
            )
        }

    @Test
    fun dirtyCancelRequiresConfirmationAndCloseReleasesOnlyOwnTransfers() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = BookSourceComposeViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.focus(0, "bookSourceName")
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            val owned = model.state.value.document!!.nativeRequest!!.path!!
            val neighbor = repository.transfer("neighbor")
            model.updateField(0, "bookSourceName", "changed", 7, 7)
            model.cancel()
            assertTrue(model.state.value.confirmDiscard)
            model.keepEditing()
            assertFalse(model.state.value.confirmDiscard)
            model.discard()
            runCurrent()
            assertTrue(model.state.value.document!!.finished)
            assertFalse(repository.transfers.containsKey(owned))
            assertEquals(mapOf(neighbor to "neighbor"), repository.transfers)
            assertFalse(GSON.toJson(repository.drafts.values.single()).contains("changed"))
        }

    @Test
    fun pausedLaunchRollsBackSamePreparedEditorAndClaimIsDeliveredOnce() =
        runTest(dispatcher) {
            val model = BookSourceComposeViewModel(FakeRepository(), SavedStateHandle(), "old")
            runCurrent()
            model.focus(0, "bookSourceName")
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            val request = model.state.value.document!!.nativeRequest!!
            var launches = 0
            assertFalse(model.deliverNative(request.id, { false }, { launches++ }))
            assertEquals(request, model.state.value.document!!.nativeRequest)
            assertTrue(model.deliverNative(request.id, { true }, { launches++ }))
            assertFalse(model.deliverNative(request.id, { true }, { launches++ }))
            assertEquals(1, launches)
        }

    @Test
    fun failedLaunchClaimRollbackCanRetryWithoutNewInputFile() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = BookSourceComposeViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.focus(0, "bookSourceName")
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            val request = model.state.value.document!!.nativeRequest!!
            repository.failWrites = true
            assertFalse(model.deliverNative(request.id, { true }, {}))
            repository.failWrites = false
            model.retry()
            runCurrent()
            assertEquals(request, model.state.value.document!!.nativeRequest)
            assertEquals(1, repository.transfers.size)
        }

    @Test
    fun editorFileResultAndCursorOnlyResultPreserveFieldOwnership() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = BookSourceComposeViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.focus(1, "bookList")
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            val request = model.state.value.document!!.nativeRequest!!
            model.deliverNative(request.id, { true }, {})
            val resultPath = repository.transfer("raw\r\n😀text")
            model.editorReturned(
                model.state.value.document!!.nativeRequest!!.id,
                true,
                null,
                resultPath,
                7,
            )
            runCurrent()
            assertEquals(
                "raw\r\n😀text",
                model.state.value.document!!.form.field(1, "bookList")!!.value,
            )
            assertEquals(7, model.state.value.document!!.form.field(1, "bookList")!!.selectionStart)
            assertTrue(repository.transfers.isEmpty())
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            model.deliverNative(model.state.value.document!!.nativeRequest!!.id, { true }, {})
            model.editorReturned(
                model.state.value.document!!.nativeRequest!!.id,
                true,
                null,
                null,
                1,
            )
            runCurrent()
            assertEquals(
                "raw\r\n😀text",
                model.state.value.document!!.form.field(1, "bookList")!!.value,
            )
            assertEquals(1, model.state.value.document!!.form.field(1, "bookList")!!.selectionStart)
        }

    @Test
    fun failedFileReadRestoresPendingResultAndRetriesWithoutReopeningEditor() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val model = BookSourceComposeViewModel(repository, savedState, "old")
            runCurrent()
            model.focus(0, "bookSourceName")
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            model.deliverNative(model.state.value.document!!.nativeRequest!!.id, { true }, {})
            repository.failRead = true
            model.editorReturned(
                model.state.value.document!!.nativeRequest!!.id,
                true,
                null,
                repository.transfer("returned"),
                3,
            )
            runCurrent()
            assertTrue(model.state.value.document!!.nativeRequest!!.returning)
            repository.failRead = false
            val restored =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(
                        mapOf("bookSourceDraftId" to savedState.get<String>("bookSourceDraftId"))
                    ),
                    "old",
                )
            runCurrent()
            assertEquals(
                "returned",
                restored.state.value.document!!.form.field(0, "bookSourceName")!!.value,
            )
            assertEquals(null, restored.state.value.document!!.nativeRequest)
        }

    @Test
    fun saveReceiptRecoveryContinuesRequestedDebugWithoutRepeatingSave() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failSaveReceipt = true }
            val model = BookSourceComposeViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.save(BookSourceSaveAction.DEBUG)
            runCurrent()
            assertEquals(1, repository.saves)
            repository.failSaveReceipt = false
            model.retry()
            runCurrent()
            assertEquals(1, repository.saves)
            assertEquals(
                BookSourceNativeAction.DEBUG,
                model.state.value.document!!.nativeRequest!!.action,
            )
            assertEquals("old", model.state.value.document!!.savedUrl)
        }

    @Test
    fun importedFieldsRetainOpenedSourceIdentityAndMetadataUntilSave() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = BookSourceComposeViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.importText(GSON.toJson(BookSource("new", "imported", customOrder = 999)))
            runCurrent()
            val document = model.state.value.document!!
            assertEquals("old", document.originalKey)
            assertEquals(17, document.original().customOrder)
            assertEquals("new", document.form.field(0, "bookSourceUrl")!!.value)
            assertTrue(document.dirty())
        }

    @Test
    fun oldQrResultCannotOverwritePasteOrConsumeNewQrOwner() =
        runTest(dispatcher) {
            val model = BookSourceComposeViewModel(FakeRepository(), SavedStateHandle(), "old")
            runCurrent()
            model.requestAction(BookSourceNativeAction.QR)
            runCurrent()
            val oldId = model.state.value.document!!.nativeRequest!!.id
            model.deliverNative(oldId, { true }, {})
            model.importText(GSON.toJson(BookSource("pasted", "paste")))
            runCurrent()
            model.qrReturned(oldId, GSON.toJson(BookSource("stale", "stale")))
            runCurrent()
            assertEquals(
                "pasted",
                model.state.value.document!!.form.field(0, "bookSourceUrl")!!.value,
            )
            model.requestAction(BookSourceNativeAction.QR)
            runCurrent()
            val newId = model.state.value.document!!.nativeRequest!!.id
            model.deliverNative(newId, { true }, {})
            model.qrReturned(oldId, GSON.toJson(BookSource("stale", "stale")))
            runCurrent()
            assertEquals(newId, model.state.value.document!!.nativeRequest!!.id)
            model.qrReturned(newId, GSON.toJson(BookSource("current", "current")))
            runCurrent()
            assertEquals(
                "current",
                model.state.value.document!!.form.field(0, "bookSourceUrl")!!.value,
            )
        }

    @Test
    fun acceptedHandoffReceiptFailureRetriesDiskWithoutRepeatingLaunch() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = BookSourceComposeViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.requestAction(BookSourceNativeAction.QR)
            runCurrent()
            val request = model.state.value.document!!.nativeRequest!!
            repository.failHandoffReceipt = true
            var launches = 0
            assertTrue(model.deliverNative(request.id, { true }, { launches++ }))
            assertTrue(model.state.value.document!!.nativeRequest!!.delivered)
            assertFalse(model.state.value.document!!.nativeRequest!!.handedOff)
            repository.failHandoffReceipt = false
            model.retry()
            runCurrent()
            assertEquals(1, launches)
            assertTrue(model.state.value.document!!.nativeRequest!!.handedOff)
            assertFalse(model.deliverNative(request.id, { true }, { launches++ }))
            assertEquals(1, launches)
        }

    @Test
    fun restoredUnprovenHandoffOffersExplicitRecoveryAndAcceptsOnlyItsActualResult() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val sessionId = UUID.randomUUID().toString()
            val ownerId = UUID.randomUUID().toString()
            val request =
                BookSourceNativeRequest(ownerId, BookSourceNativeAction.QR, delivered = true)
            repository.drafts[sessionId] =
                BookSourceEditDocument.from(BookSource("old", "name"))
                    .copy(nativeRequest = request, revision = 4)
            val model =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(mapOf("bookSourceDraftId" to sessionId)),
                    "old",
                )
            runCurrent()
            assertTrue(model.state.value.error!!.contains("交付中断"))
            assertEquals(request, model.state.value.document!!.nativeRequest)
            model.qrReturned("other owner", GSON.toJson(BookSource("wrong", "wrong")))
            runCurrent()
            assertEquals("old", model.state.value.document!!.form.field(0, "bookSourceUrl")!!.value)
            assertTrue(model.state.value.error!!.contains("交付中断"))
            model.qrReturned(ownerId, GSON.toJson(BookSource("actual", "actual")))
            runCurrent()
            assertEquals(
                "actual",
                model.state.value.document!!.form.field(0, "bookSourceUrl")!!.value,
            )
            assertEquals(null, model.state.value.document!!.nativeRequest)
        }

    @Test
    fun restoredUnprovenHandoffWaitsForManualRetryBeforeBecomingLaunchable() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val sessionId = UUID.randomUUID().toString()
            val request =
                BookSourceNativeRequest(
                    UUID.randomUUID().toString(),
                    BookSourceNativeAction.QR,
                    delivered = true,
                )
            repository.drafts[sessionId] =
                BookSourceEditDocument.from(BookSource("old", "name"))
                    .copy(nativeRequest = request, revision = 4)
            val model =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(mapOf("bookSourceDraftId" to sessionId)),
                    "old",
                )
            runCurrent()
            var launches = 0
            assertFalse(model.deliverNative(request.id, { true }, { launches++ }))
            assertEquals(0, launches)
            model.retry()
            runCurrent()
            assertEquals(null, model.state.value.error)
            assertFalse(model.state.value.document!!.nativeRequest!!.delivered)
            assertEquals(request.id, model.state.value.document!!.nativeRequest!!.id)
            assertTrue(model.deliverNative(request.id, { true }, { launches++ }))
            assertEquals(1, launches)
        }

    @Test
    fun lateEditorResultCannotConsumeNewSameTypeOwner() =
        runTest(dispatcher) {
            val model = BookSourceComposeViewModel(FakeRepository(), SavedStateHandle(), "old")
            runCurrent()
            model.focus(0, "bookSourceName")
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            val oldId = model.state.value.document!!.nativeRequest!!.id
            model.deliverNative(oldId, { true }, {})
            model.editorReturned(oldId, false, null, null, -1)
            runCurrent()
            model.requestAction(BookSourceNativeAction.EDITOR)
            runCurrent()
            val newId = model.state.value.document!!.nativeRequest!!.id
            model.deliverNative(newId, { true }, {})
            model.editorReturned(oldId, true, "stale", null, 0)
            runCurrent()
            assertEquals(newId, model.state.value.document!!.nativeRequest!!.id)
            assertEquals(
                "name",
                model.state.value.document!!.form.field(0, "bookSourceName")!!.value,
            )
        }

    @Test
    fun rejectedOldNativeClaimCannotLaunchOrOverwriteNewOwnerAndRetryLoadsDurableDraft() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val handle = SavedStateHandle()
            val model = BookSourceComposeViewModel(repository, handle, "old")
            runCurrent()
            model.requestAction(BookSourceNativeAction.QR)
            runCurrent()
            val stale = model.state.value.document!!
            val replacement =
                stale.copy(
                    revision = stale.revision + 1,
                    nativeRequest =
                        BookSourceNativeRequest(
                            UUID.randomUUID().toString(),
                            BookSourceNativeAction.QR,
                        ),
                    form = stale.form.updateField(0, "bookSourceName", "new owner", 0, 0),
                )
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            repository.beforeWrite = {
                entered.complete(Unit)
                resume.await()
            }
            var launches = 0
            val oldClaim = async {
                model.deliverNative(stale.nativeRequest!!.id, { true }, { launches++ })
            }
            runCurrent()
            entered.await()
            repository.drafts[handle.get<String>("bookSourceDraftId")!!] = replacement
            repository.beforeWrite = {}
            resume.complete(Unit)
            assertFalse(oldClaim.await())
            assertEquals(0, launches)
            assertEquals(replacement, repository.drafts[handle.get<String>("bookSourceDraftId")!!])
            assertEquals(null, model.state.value.document)
            assertTrue(model.state.value.error!!.contains("其他编辑会话"))
            model.retry()
            runCurrent()
            assertEquals(replacement, model.state.value.document)
            assertEquals(null, model.state.value.error)
        }

    private fun migrationPreview(
        manual: Boolean = false,
        candidate: String =
            """{"schemaVersion":1,"id":"old","name":"candidate","baseUrl":"https://fixture.invalid"}""",
    ) =
        SourceMigrationPreview(
            if (manual)
                listOf(SourceMigrationIssue("ruleSearch.name", "legacy.unsupported", "需要手工迁移"))
            else emptyList(),
            candidate,
            manual,
            if (manual) "manualRequired" else "unverified",
        )

    @Test
    fun migrationUsesCurrentDraftWithoutSavingAndAppliesOnlyToCommentDraft() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            var snapshot: BookSource? = null
            val model =
                BookSourceComposeViewModel(
                    repository,
                    savedState,
                    "old",
                    migrationAvailable = true,
                    migrateSource = {
                        snapshot = it
                        migrationPreview()
                    },
                )
            runCurrent()
            model.updateField(0, "bookSourceName", "edited", 6, 6)
            model.updateField(0, "bookSourceComment", "user comment", 12, 12)
            runCurrent()
            model.previewMigration()
            runCurrent()
            assertEquals("edited", snapshot!!.bookSourceName)
            assertEquals("user comment", snapshot!!.bookSourceComment)
            assertEquals("user comment", model.state.value.document!!.source().bookSourceComment)
            assertEquals(0, repository.saves)
            assertEquals(setOf("bookSourceDraftId"), savedState.keys())
            model.applyMigration()
            runCurrent()
            val applied = model.state.value.document!!
            assertTrue(applied.source().bookSourceComment!!.startsWith("@source:v1 "))
            assertTrue(applied.source().bookSourceComment!!.endsWith("user comment"))
            assertEquals("edited", applied.source().bookSourceName)
            assertTrue(applied.dirty())
            assertEquals(0, repository.saves)
            assertEquals(null, model.state.value.migrationReport)
            model.focus(0, "bookSourceComment")
            model.undo()
            assertEquals("user comment", model.state.value.document!!.source().bookSourceComment)
        }

    @Test
    fun migrationManualReportCannotApplyCandidate() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    "old",
                    migrationAvailable = true,
                    migrateSource = { migrationPreview(manual = true) },
                )
            runCurrent()
            val before = model.state.value.document
            model.previewMigration()
            runCurrent()
            model.applyMigration()
            runCurrent()
            assertEquals(before, model.state.value.document)
            assertTrue(model.state.value.migrationReport!!.preview.requiresManualWork)
            assertEquals(0, repository.saves)
        }

    @Test
    fun asynchronousMigrationCannotApplyAfterDraftRevisionChanges() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<SourceMigrationPreview>()
            val model =
                BookSourceComposeViewModel(
                    FakeRepository(),
                    SavedStateHandle(),
                    "old",
                    migrationAvailable = true,
                    migrateSource = { pending.await() },
                )
            runCurrent()
            model.previewMigration()
            runCurrent()
            assertTrue(model.state.value.migrationRunning)
            model.updateField(0, "bookSourceName", "changed later", 13, 13)
            pending.complete(migrationPreview())
            runCurrent()
            val before = model.state.value.document
            assertTrue(model.state.value.migrationReport!!.revision != before!!.revision)
            model.applyMigration()
            runCurrent()
            assertEquals(before, model.state.value.document)
        }

    @Test
    fun migrationFailureAndMalformedCandidateLeaveOriginalDraftUntouched() =
        runTest(dispatcher) {
            var failure = true
            val model =
                BookSourceComposeViewModel(
                    FakeRepository(),
                    SavedStateHandle(),
                    "old",
                    migrationAvailable = true,
                    migrateSource = {
                        if (failure) error("migration failed")
                        else migrationPreview(candidate = "[]")
                    },
                )
            runCurrent()
            val before = model.state.value.document
            model.previewMigration()
            runCurrent()
            assertEquals("migration failed", model.state.value.migrationError)
            assertEquals(before, model.state.value.document)
            failure = false
            model.previewMigration()
            runCurrent()
            model.applyMigration()
            runCurrent()
            assertTrue(model.state.value.migrationError != null)
            assertEquals(before, model.state.value.document)
        }

    @Test
    fun dismissingMigrationRejectsLateResultAndReportIsNotRestored() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<SourceMigrationPreview>()
            val repository = FakeRepository()
            val saved = SavedStateHandle()
            val model =
                BookSourceComposeViewModel(
                    repository,
                    saved,
                    "old",
                    migrationAvailable = true,
                    migrateSource = { pending.await() },
                )
            runCurrent()
            model.previewMigration()
            runCurrent()
            model.dismissMigration()
            pending.complete(migrationPreview())
            runCurrent()
            assertEquals(null, model.state.value.migrationReport)
            assertFalse(model.state.value.migrationRunning)
            val restored =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(
                        mapOf("bookSourceDraftId" to saved.get<String>("bookSourceDraftId"))
                    ),
                    "old",
                    migrationAvailable = true,
                    migrateSource = { migrationPreview() },
                )
            runCurrent()
            assertEquals(null, restored.state.value.migrationReport)
            assertEquals(setOf("bookSourceDraftId"), saved.keys())
        }

    @Test
    fun unavailableBuildDoesNotRequestMigration() =
        runTest(dispatcher) {
            var calls = 0
            val model =
                BookSourceComposeViewModel(
                    FakeRepository(),
                    SavedStateHandle(),
                    "old",
                    migrationAvailable = false,
                    migrateSource = {
                        calls++
                        migrationPreview()
                    },
                )
            runCurrent()
            model.previewMigration()
            runCurrent()
            assertEquals(0, calls)
            assertFalse(model.state.value.migrationRunning)
        }

    @Test
    fun existingModernCandidateIsPreservedAndDoesNotCallMigrationBackend() =
        runTest(dispatcher) {
            var calls = 0
            val repository = FakeRepository()
            val model =
                BookSourceComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    "old",
                    migrationAvailable = true,
                    migrateSource = {
                        calls++
                        migrationPreview()
                    },
                )
            runCurrent()
            val comment =
                """@source:v1 {"schemaVersion":1,"id":"existing-modern","script":"modern payload"}
用户说明"""
            model.updateField(0, "bookSourceComment", comment, comment.length, comment.length)
            runCurrent()
            val before = model.state.value.document
            model.previewMigration()
            runCurrent()
            model.applyMigration()
            runCurrent()
            assertEquals(0, calls)
            assertEquals(before, model.state.value.document)
            assertEquals(comment, model.state.value.document!!.source().bookSourceComment)
            assertEquals("已使用新版配置，无需再次从旧字段迁移", model.state.value.migrationError)
            assertEquals(null, model.state.value.migrationReport)
            assertFalse(model.state.value.migrationRunning)
            assertEquals(0, repository.saves)
        }

    private class FakeRepository : BookSourceEditorRepository {
        val drafts = mutableMapOf<String, BookSourceEditDocument>()
        val transfers = mutableMapOf<String, String>()
        var beforeWrite: suspend () -> Unit = {}
        var failWrites = false
        var failHandoffReceipt = false
        var failRead = false
        var failSaveReceipt = false
        var saves = 0
        private var pending: BookSourceEditDocument? = null

        override fun assists(): Flow<List<BookSourceKeyboardAssist>> = flowOf(emptyList())

        override suspend fun load(sourceUrl: String?) =
            BookSourceEditDocument.from(BookSource("old", "name", customOrder = 17))

        override suspend fun readDraft(sessionId: String): BookSourceEditDocument? {
            pending?.let {
                drafts[sessionId] = it
                pending = null
            }
            return drafts[sessionId]
        }

        override suspend fun writeDraft(
            sessionId: String,
            document: BookSourceEditDocument,
        ): Boolean {
            if (failWrites || (failHandoffReceipt && document.nativeRequest?.handedOff == true))
                error("disk failed")
            beforeWrite()
            val previous = drafts[sessionId]
            if (previous == document) return true
            if (previous?.finished == true || (previous?.revision ?: -1) >= document.revision)
                return false
            drafts[sessionId] = document
            return true
        }

        override suspend fun save(
            sessionId: String,
            document: BookSourceEditDocument,
            action: BookSourceSaveAction,
        ): BookSourceEditDocument {
            saves++
            val result =
                document.copy(
                    savedUrl = "old",
                    revision = document.revision + 1,
                    delivery = BookSourceSaveDelivery(UUID.randomUUID().toString(), action, "old"),
                )
            if (failSaveReceipt) {
                pending = result
                error("receipt failed")
            }
            drafts[sessionId] = result
            return result
        }

        override suspend fun importForm(text: String): BookSourceEditForm =
            projectBookSourceEditForm(GSON.fromJson(text, BookSource::class.java))

        override suspend fun searchScope(sourceUrl: String): String = "name::$sourceUrl"

        override suspend fun parse(text: String): String = text

        override suspend fun export(document: BookSourceEditDocument): String =
            GSON.toJson(document.source())

        override suspend fun groups(): List<String> = listOf("group")

        override suspend fun clearCookie(sourceUrl: String) = Unit

        override suspend fun variableComment(sourceUrl: String): String = "comment"

        override suspend fun variable(sourceUrl: String): String = "variable"

        override suspend fun setVariable(sourceUrl: String, value: String?) = Unit

        override suspend fun transfer(text: String): String =
            UUID.randomUUID().toString().also { transfers[it] = text }

        override suspend fun editorText(path: String): String {
            if (failRead) error("read failed")
            return transfers.getValue(path)
        }

        override suspend fun release(vararg paths: String?) {
            paths.forEach { transfers.remove(it) }
        }
    }
}
