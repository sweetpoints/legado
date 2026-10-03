package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.GSON
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
            model.editorReturned(true, null, resultPath, 7)
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
            model.editorReturned(true, null, null, 1)
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
            model.editorReturned(true, null, repository.transfer("returned"), 3)
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

    private class FakeRepository : BookSourceEditorRepository {
        val drafts = mutableMapOf<String, BookSourceEditDocument>()
        val transfers = mutableMapOf<String, String>()
        var failWrites = false
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

        override suspend fun writeDraft(sessionId: String, document: BookSourceEditDocument) {
            if (failWrites) error("disk failed")
            if ((drafts[sessionId]?.revision ?: -1) < document.revision)
                drafts[sessionId] = document
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
