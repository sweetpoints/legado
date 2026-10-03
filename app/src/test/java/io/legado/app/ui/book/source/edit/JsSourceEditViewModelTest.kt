package io.legado.app.ui.book.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.BookSource
import io.legado.app.model.jsSource.JsSourceUpsert
import io.legado.app.ui.code.CodeEditActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class JsSourceEditViewModelTest {
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
    fun largeSourceRestoresFromDraftWithOnlyUuidInSavedState() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { initialText = "js".repeat(500_000) }
            val savedState = SavedStateHandle()
            val first = JsSourceEditViewModel(repository, savedState, "old")
            runCurrent()
            first.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            val restored = JsSourceEditViewModel(repository, copyState(savedState), "old")
            runCurrent()
            assertEquals(JsSourceEditStage.EDITOR_OPEN, restored.state.value.stage)
            assertEquals(first.state.value.editorPath, restored.state.value.editorPath)
            assertEquals(repository.initialText, repository.drafts.values.single().text)
            assertEquals(setOf("jsSourceDraftId"), savedState.keys())
            assertTrue(savedState.get<String>("jsSourceDraftId")!!.length < 100)
            assertEquals(1, repository.transfers.size)
        }

    @Test
    fun pausedHostKeepsSameTransferAndDoesNotLaunch() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            var launches = 0
            val transferPath = model.state.value.editorPath
            assertFalse(
                model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { false }, { launches++ })
            )
            assertEquals(JsSourceEditStage.READY, model.state.value.stage)
            assertEquals(transferPath, model.state.value.editorPath)
            assertEquals(0, launches)
            assertTrue(model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, { launches++ }))
            assertEquals(1, launches)
            assertFalse(
                model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, { launches++ })
            )
        }

    @Test
    fun editorFileResultIsSavedBeforeDebugAndUsesUpdatedSourceUrl() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val model = JsSourceEditViewModel(repository, savedState, "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            val resultPath = repository.transfer("updated script")
            model.editorReturned(
                true,
                null,
                resultPath,
                CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE,
            )
            runCurrent()
            assertEquals(listOf("updated script" to "old"), repository.saves)
            assertEquals("new", model.state.value.sourceUrl)
            assertEquals(JsSourceEditStage.DEBUG_READY, model.state.value.stage)
            assertTrue(repository.transfers.isEmpty())
            val restored = JsSourceEditViewModel(repository, copyState(savedState), "old")
            runCurrent()
            restored.deliverLaunch(JsSourceEditStage.DEBUG_OPEN, { true }, {})
            restored.returned(JsSourceEditStage.DEBUG_OPEN)
            runCurrent()
            assertEquals(JsSourceEditStage.READY, restored.state.value.stage)
            assertEquals("updated script", repository.transfers.values.single())
            assertEquals(1, repository.saves.size)
        }

    @Test
    fun failedSaveKeepsEditableSourceAndRetryDoesNotReexecuteSave() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failSave = true }
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, "bad script", null, null)
            runCurrent()
            assertEquals(JsSourceEditStage.READY, model.state.value.stage)
            assertEquals("parse failed", model.state.value.error)
            assertEquals("bad script", repository.transfers.values.single())
            model.load()
            runCurrent()
            assertEquals(1, repository.saves.size)
            assertEquals(1, repository.transfers.size)
            assertEquals(null, model.state.value.error)
        }

    @Test
    fun missingLoginRuleReturnsToEditorWithoutLaunchingLogin() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, "script", null, CodeEditActivity.RESULT_ACTION_LOGIN_SOURCE)
            runCurrent()
            assertEquals(JsSourceEditStage.READY, model.state.value.stage)
            assertTrue(model.state.value.saved)
            assertTrue(model.state.value.missingLogin)
            assertFalse(model.deliverLaunch(JsSourceEditStage.LOGIN_OPEN, { true }, {}))
            model.load()
            runCurrent()
            assertTrue(model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {}))
        }

    @Test
    fun cancelAfterDebugPreservesSuccessfulResultAndReleasesOnlyOwnEditorFile() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val model = JsSourceEditViewModel(repository, savedState, "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, "script", null, CodeEditActivity.RESULT_ACTION_DEBUG_SOURCE)
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.DEBUG_OPEN, { true }, {})
            model.returned(JsSourceEditStage.DEBUG_OPEN)
            runCurrent()
            val neighborPath = repository.transfer("neighbor")
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(false, null, null, null)
            runCurrent()
            assertTrue(model.state.value.finished)
            assertTrue(model.state.value.saved)
            assertEquals("new", model.state.value.sourceUrl)
            assertEquals(mapOf(neighborPath to "neighbor"), repository.transfers)
            assertEquals("", repository.drafts.values.single().text)
            val restored = JsSourceEditViewModel(repository, copyState(savedState), "old")
            runCurrent()
            assertTrue(restored.state.value.finished)
            assertTrue(restored.state.value.saved)
        }

    @Test
    fun ordinarySaveFinishesAndDuplicateResultCannotSaveAgain() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, "script", null, null)
            runCurrent()
            model.editorReturned(true, "late", null, null)
            runCurrent()
            assertEquals(1, repository.saves.size)
            assertTrue(model.state.value.finished)
            assertEquals("", repository.drafts.values.single().text)
        }

    @Test
    fun cursorOnlyOkResultMeansExitWithoutSaving() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, null, null, null)
            runCurrent()
            assertTrue(model.state.value.finished)
            assertFalse(model.state.value.saved)
            assertTrue(repository.saves.isEmpty())
            assertTrue(repository.transfers.isEmpty())
        }

    @Test
    fun launchFailureCanRetrySamePreparedFile() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            val originalPath = model.state.value.editorPath
            assertFalse(
                model.deliverLaunch(
                    JsSourceEditStage.EDITOR_OPEN,
                    { true },
                    { error("launch failed") },
                )
            )
            assertEquals("launch failed", model.state.value.error)
            model.load()
            runCurrent()
            assertEquals(originalPath, model.state.value.editorPath)
            assertEquals(1, repository.transfers.size)
        }

    @Test
    fun acceptedReceiptFailureRetryDoesNotParseOrSaveAgain() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failReceipt = true }
            val model = JsSourceEditViewModel(repository, SavedStateHandle(), "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, "script", null, null)
            runCurrent()
            assertEquals("receipt failed", model.state.value.error)
            assertEquals(JsSourceEditStage.SAVING, model.state.value.stage)
            repository.failReceipt = false
            model.load()
            runCurrent()
            assertTrue(model.state.value.finished)
            assertTrue(model.state.value.saved)
            assertEquals("new", model.state.value.sourceUrl)
            assertEquals(1, repository.saves.size)
        }

    @Test
    fun acceptedReceiptSurvivesViewModelClearBeforeReturningToMain() =
        runTest(dispatcher) {
            val receiptGate = CompletableDeferred<Unit>()
            val repository = FakeRepository().apply { this.receiptGate = receiptGate }
            val savedState = SavedStateHandle()
            val model = JsSourceEditViewModel(repository, savedState, "old")
            val viewModelStore = ViewModelStore().apply { put("editor", model) }
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            model.editorReturned(true, "script", null, null)
            runCurrent()
            assertEquals(1, repository.saves.size)
            assertFalse(repository.drafts.values.single().saved)
            viewModelStore.clear()
            receiptGate.complete(Unit)
            runCurrent()
            assertTrue(repository.drafts.values.single().finished)
            assertTrue(repository.drafts.values.single().saved)
            val restored = JsSourceEditViewModel(repository, copyState(savedState), "old")
            runCurrent()
            assertTrue(restored.state.value.finished)
            assertEquals("new", restored.state.value.sourceUrl)
            assertEquals(1, repository.saves.size)
        }

    @Test
    fun editorFileReadFailureRestoresPendingResultAndRetriesWithoutRelauch() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failEditorRead = true }
            val savedState = SavedStateHandle()
            val model = JsSourceEditViewModel(repository, savedState, "old")
            runCurrent()
            model.deliverLaunch(JsSourceEditStage.EDITOR_OPEN, { true }, {})
            val returnedPath = repository.transfer("returned script")
            model.editorReturned(true, null, returnedPath, null)
            runCurrent()
            assertEquals("result read failed", model.state.value.error)
            assertTrue(repository.drafts.values.single().editorReturning)
            assertTrue(repository.saves.isEmpty())
            repository.failEditorRead = false
            val restored = JsSourceEditViewModel(repository, copyState(savedState), "old")
            runCurrent()
            assertTrue(restored.state.value.finished)
            assertEquals(listOf("returned script" to "old"), repository.saves)
            assertTrue(repository.transfers.isEmpty())
        }

    @Test
    fun terminalDraftRestorationReleasesRegisteredFilesAfterInterruptedCleanup() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val savedState = SavedStateHandle()
            val model = JsSourceEditViewModel(repository, savedState, "old")
            runCurrent()
            val editorPath = model.state.value.editorPath!!
            val sessionId = savedState.get<String>("jsSourceDraftId")!!
            repository.drafts[sessionId] =
                repository.drafts
                    .getValue(sessionId)
                    .copy(
                        text = "",
                        editorPath = null,
                        finished = true,
                    )
            val neighborPath = repository.transfer("neighbor")
            val restored = JsSourceEditViewModel(repository, copyState(savedState), "old")
            runCurrent()
            assertTrue(restored.state.value.finished)
            assertFalse(repository.transfers.containsKey(editorPath))
            assertEquals(mapOf(neighborPath to "neighbor"), repository.transfers)
        }

    @Test
    fun interruptedSaveStagesRequireExplicitRetryAfterRestoration() =
        runTest(dispatcher) {
            val stages =
                listOf(
                    JsSourceEditStage.SAVING,
                    JsSourceEditStage.SAVING_FOR_DEBUG,
                    JsSourceEditStage.SAVING_FOR_LOGIN,
                )
            stages.forEach { interruptedStage ->
                val repository = FakeRepository()
                val sessionId = java.util.UUID.randomUUID().toString()
                repository.drafts[sessionId] =
                    JsSourceDraft(
                        text = "interrupted script",
                        sourceUrl = "old",
                        stage = interruptedStage,
                        revision = 5,
                    )
                val model =
                    JsSourceEditViewModel(
                        repository,
                        SavedStateHandle(mapOf("jsSourceDraftId" to sessionId)),
                        "old",
                    )
                runCurrent()
                assertTrue(repository.saves.isEmpty())
                assertEquals(interruptedStage, model.state.value.stage)
                assertTrue(model.state.value.error!!.contains("点击重试"))
                assertFalse(model.state.value.busy)
                assertEquals(5L, repository.drafts.getValue(sessionId).revision)
                model.load()
                runCurrent()
                assertEquals(listOf("interrupted script" to "old"), repository.saves)
                assertTrue(model.state.value.saved)
            }
        }

    private fun copyState(savedState: SavedStateHandle): SavedStateHandle {
        return SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) })
    }

    private class FakeRepository : JsSourceEditRepository {
        var initialText = "initial script"
        var failSave = false
        var failReceipt = false
        var failEditorRead = false
        var receiptGate: CompletableDeferred<Unit>? = null
        val drafts = mutableMapOf<String, JsSourceDraft>()
        val transfers = mutableMapOf<String, String>()
        val saves = mutableListOf<Pair<String, String?>>()
        private var transferCounter = 0

        override suspend fun read(sessionId: String): JsSourceDraft? = drafts[sessionId]

        override suspend fun write(sessionId: String, draft: JsSourceDraft) {
            if (draft.saved) {
                if (failReceipt) error("receipt failed")
                receiptGate?.await()
            }
            drafts[sessionId] = draft
        }

        override suspend fun initial(sourceUrl: String?): String = initialText

        override suspend fun save(
            text: String,
            sourceUrl: String?,
            onAccepted: suspend (BookSource) -> Unit,
        ): BookSource {
            saves += text to sourceUrl
            if (failSave) error("parse failed")
            return JsSourceUpsert.acceptedWrite(onAccepted) {
                BookSource(bookSourceUrl = "new", mainJs = text)
            }
        }

        override suspend fun editorText(path: String): String {
            if (failEditorRead) error("result read failed")
            return transfers.getValue(path)
        }

        override suspend fun transfer(text: String): String {
            val path = "transfer-${++transferCounter}"
            transfers[path] = text
            return path
        }

        override suspend fun release(path: String?) {
            transfers.remove(path)
        }
    }
}
