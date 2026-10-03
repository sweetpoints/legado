package io.legado.app.ui.code

import androidx.lifecycle.SavedStateHandle
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class CodeEditorComposeViewModelTest {
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
    fun privateUuidBootstrapRestoresRawDraftWithoutReadingLaunchAgain() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val saved = SavedStateHandle()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    saved,
                    CodeEditorLaunch(text = "😀\r\ninitial"),
                )
            runCurrent()
            model.updateEditor(model.state.value.engineOwner!!, "😀\r\nchanged", 9, 1)
            runCurrent()
            val id = saved.get<String>("codeEditorSessionId")!!
            val restored =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(mapOf("codeEditorSessionId" to id)),
                    CodeEditorLaunch(text = "wrong new input"),
                )
            runCurrent()
            assertEquals(setOf("codeEditorSessionId"), saved.keys())
            assertEquals("😀\r\nchanged", restored.state.value.session!!.text)
            assertEquals(CodeEditorSelection(9, 1), restored.state.value.session!!.selection)
            assertEquals(1, repository.loads)
        }

    @Test
    fun failedInitialWriteRetriesDetachedBootstrapInsteadOfReadingLegacyPayloadTwice() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failWrites = true }
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw"),
                )
            runCurrent()
            assertEquals(null, model.state.value.session)
            repository.failWrites = false
            model.retry()
            runCurrent()
            assertEquals("raw", model.state.value.session!!.text)
            assertEquals(1, repository.loads)
        }

    @Test
    fun missingRestoredUuidNeverBorrowsIncomingLaunchPayload() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(mapOf("codeEditorSessionId" to UUID.randomUUID().toString())),
                    CodeEditorLaunch(text = "unrelated"),
                )
            runCurrent()
            assertEquals(0, repository.loads)
            assertEquals(null, model.state.value.session)
            assertTrue(model.state.value.error!!.contains("已丢失"))
        }

    @Test
    fun readOnlyExitClearsPrivateCodeWithoutReturningTextOrCursor() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw", readOnly = true, cursorPosition = 2),
                )
            runCurrent()
            model.updateEditor(model.state.value.engineOwner!!, "changed", 0, 0)
            assertEquals("raw", model.state.value.session!!.text)
            model.requestExit()
            runCurrent()
            assertTrue(model.state.value.session!!.finished)
            assertEquals("", model.state.value.session!!.text)
            assertEquals(null, model.state.value.session!!.returnReceipt)
        }

    @Test
    fun unchangedCursorOnlyReturnPreservesLegacyContractAndPauseKeepsSameReceipt() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw", cursorPosition = 2),
                )
            runCurrent()
            model.requestExit()
            runCurrent()
            val receipt = model.state.value.session!!.returnReceipt!!
            var returns = 0
            assertFalse(model.deliverReturn(receipt.id, { false }, { returns++ }))
            assertEquals(receipt.id, model.state.value.session!!.returnReceipt!!.id)
            assertFalse(model.state.value.session!!.returnReceipt!!.claimed)
            assertEquals(0, returns)
            assertTrue(
                model.deliverReturn(
                    receipt.id,
                    { true },
                    { payload ->
                        returns++
                        assertEquals(2, payload.cursorPosition)
                        assertEquals(null, payload.text)
                        assertEquals(null, payload.textFile)
                    },
                )
            )
            assertEquals(1, returns)
            assertTrue(model.state.value.session!!.finished)
        }

    @Test
    fun unchangedExplicitSaveCanReturnFullTextWithOriginalPublicFlag() =
        runTest(dispatcher) {
            val model =
                CodeEditorComposeViewModel(
                    FakeRepository(),
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw\r\n", returnUnchangedText = true),
                )
            runCurrent()
            model.save()
            runCurrent()
            val receipt = model.state.value.session!!.returnReceipt!!
            assertTrue(
                model.deliverReturn(receipt.id, { true }, { assertEquals("raw\r\n", it.text) })
            )
        }

    @Test
    fun dirtyBackConfirmationCanKeepEditingOrDiscardWithCursorOnlyReturn() =
        runTest(dispatcher) {
            val model =
                CodeEditorComposeViewModel(
                    FakeRepository(),
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "initial"),
                )
            runCurrent()
            model.updateEditor(model.state.value.engineOwner!!, "changed", 4, 1)
            runCurrent()
            model.requestExit()
            assertTrue(model.state.value.confirmDiscard)
            model.keepEditing()
            assertFalse(model.state.value.confirmDiscard)
            assertEquals("changed", model.state.value.session!!.text)
            model.requestExit()
            model.discard()
            runCurrent()
            val receipt = model.state.value.session!!.returnReceipt!!
            assertFalse(receipt.includeText)
            assertTrue(
                model.deliverReturn(
                    receipt.id,
                    { true },
                    {
                        assertEquals(1, it.cursorPosition)
                        assertEquals(null, it.text)
                    },
                )
            )
        }

    @Test
    fun outputFailureRetriesSamePrivateReceiptAndDeterministicFileBeforeDebugReturn() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failOutput = true }
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "initial", useTextFile = true, showDebugSource = true),
                )
            runCurrent()
            model.updateEditor(model.state.value.engineOwner!!, "raw\r\nchanged", 4, 4)
            runCurrent()
            model.save("debugSource")
            runCurrent()
            val receipt = model.state.value.session!!.returnReceipt!!
            assertFalse(receipt.prepared)
            repository.failOutput = false
            model.retry()
            runCurrent()
            assertEquals(receipt.id, model.state.value.session!!.returnReceipt!!.id)
            assertEquals(receipt.textFile, model.state.value.session!!.returnReceipt!!.textFile)
            assertTrue(
                model.deliverReturn(
                    receipt.id,
                    { true },
                    {
                        assertEquals("debugSource", it.action)
                        assertEquals(receipt.textFile, it.textFile)
                        assertEquals(null, it.text)
                    },
                )
            )
        }

    @Test
    fun acceptedReturnCleanupFailureRetriesOnlyCloseWithoutRepeatingPublicDelivery() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw", returnUnchangedText = true),
                )
            runCurrent()
            model.save()
            runCurrent()
            val id = model.state.value.session!!.returnReceipt!!.id
            repository.failClose = true
            var returns = 0
            assertTrue(model.deliverReturn(id, { true }, { returns++ }))
            assertEquals(1, returns)
            assertFalse(model.deliverReturn(id, { true }, { returns++ }))
            repository.failClose = false
            model.retry()
            runCurrent()
            assertTrue(model.state.value.session!!.finished)
            assertEquals(1, returns)
        }

    @Test
    fun restoredClaimedResultRequiresManualRetryAndNeverAutoReturns() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val id = UUID.randomUUID().toString()
            val receipt =
                CodeEditorReturnReceipt(
                    UUID.randomUUID().toString(),
                    1,
                    true,
                    claimed = true,
                    prepared = true,
                )
            repository.sessions[id] =
                CodeEditorSession("raw", returnReceipt = receipt, revision = 4)
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(mapOf("codeEditorSessionId" to id)),
                    null,
                )
            runCurrent()
            assertTrue(model.state.value.error!!.contains("交付中断"))
            var returns = 0
            assertFalse(model.deliverReturn(receipt.id, { true }, { returns++ }))
            model.retry()
            runCurrent()
            assertEquals(0, returns)
            assertFalse(model.state.value.session!!.returnReceipt!!.claimed)
            assertTrue(model.deliverReturn(receipt.id, { true }, { returns++ }))
            assertEquals(1, returns)
        }

    @Test
    fun gatedOldClaimCannotReturnOrRollBackNewerPrivateOwner() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val saved = SavedStateHandle()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    saved,
                    CodeEditorLaunch(text = "raw", returnUnchangedText = true),
                )
            runCurrent()
            model.save()
            runCurrent()
            val current = model.state.value.session!!
            val replacement =
                current.copy(
                    text = "new owner",
                    revision = current.revision + 1,
                    returnReceipt = current.returnReceipt!!.copy(id = UUID.randomUUID().toString()),
                )
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            repository.beforeWrite = {
                entered.complete(Unit)
                resume.await()
            }
            var returns = 0
            val oldClaim = async {
                model.deliverReturn(current.returnReceipt!!.id, { true }, { returns++ })
            }
            runCurrent()
            entered.await()
            repository.sessions[saved.get<String>("codeEditorSessionId")!!] = replacement
            repository.beforeWrite = {}
            resume.complete(Unit)
            assertFalse(oldClaim.await())
            assertEquals(0, returns)
            assertEquals(
                replacement,
                repository.sessions[saved.get<String>("codeEditorSessionId")!!],
            )
            assertEquals(null, model.state.value.session)
        }

    @Test
    fun abandoningFailedUnclaimedOutputClosesAndReleasesOnlyItsOwnedFile() =
        runTest(dispatcher) {
            val repository = FakeRepository().apply { failOutput = true }
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw", returnUnchangedText = true, useTextFile = true),
                )
            runCurrent()
            model.save()
            runCurrent()
            val output = model.state.value.session!!.returnReceipt!!.textFile
            model.discard()
            runCurrent()
            assertTrue(model.state.value.session!!.finished)
            assertEquals(listOf(output), repository.released)
        }

    @Test
    fun retiredNativeEngineCannotEditOrMarkReadyAfterPrivateOwnerReload() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val saved = SavedStateHandle()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    saved,
                    CodeEditorLaunch(text = "raw", returnUnchangedText = true),
                )
            runCurrent()
            val retiredEngine = model.state.value.engineOwner!!
            model.save()
            runCurrent()
            val current = model.state.value.session!!
            val replacement =
                current.copy(
                    text = "new owner",
                    revision = current.revision + 1,
                    returnReceipt = null,
                )
            repository.sessions[saved.get<String>("codeEditorSessionId")!!] = replacement
            assertFalse(model.deliverReturn(current.returnReceipt!!.id, { true }, {}))
            model.retry()
            runCurrent()
            assertEquals(replacement, model.state.value.session)
            assertFalse(retiredEngine == model.state.value.engineOwner)
            model.updateEditor(retiredEngine, "stale code", 0, 0)
            model.editorReady(retiredEngine, true)
            assertEquals("new owner", model.state.value.session!!.text)
            assertFalse(model.state.value.editorReady)
        }

    @Test
    fun readOnlyProgrammaticFormattingCanPersistWhileUserEditingRemainsBlocked() =
        runTest(dispatcher) {
            val model =
                CodeEditorComposeViewModel(
                    FakeRepository(),
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw", readOnly = true),
                )
            runCurrent()
            val owner = model.state.value.engineOwner!!
            model.updateEditor(owner, "user mutation", 0, 0)
            assertEquals("raw", model.state.value.session!!.text)
            model.updateEditor(owner, "formatted preview", 2, 2, programmatic = true)
            runCurrent()
            assertEquals("formatted preview", model.state.value.session!!.text)
            model.requestExit()
            runCurrent()
            assertTrue(model.state.value.session!!.finished)
            assertEquals(null, model.state.value.session!!.returnReceipt)
        }

    @Test
    fun explicitEngineRestartRetiresOldCallbacksWithoutReloadingLaunchPayload() =
        runTest(dispatcher) {
            val repository = FakeRepository()
            val model =
                CodeEditorComposeViewModel(
                    repository,
                    SavedStateHandle(),
                    CodeEditorLaunch(text = "raw"),
                )
            runCurrent()
            val oldOwner = model.state.value.engineOwner!!
            model.restartEngine(oldOwner)
            runCurrent()
            assertFalse(oldOwner == model.state.value.engineOwner)
            model.updateEditor(oldOwner, "stale callback", 0, 0)
            assertEquals("raw", model.state.value.session!!.text)
            assertEquals(1, repository.loads)
        }

    private class FakeRepository : CodeEditorSessionRepository {
        val sessions = mutableMapOf<String, CodeEditorSession>()
        val released = mutableListOf<String?>()
        var loads = 0
        var failWrites = false
        var failOutput = false
        var failClose = false
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun loadLaunch(launch: CodeEditorLaunch): CodeEditorSession {
            loads++
            return CodeEditorSession(
                launch.text!!,
                selection = CodeEditorSelection(launch.cursorPosition),
                writable = !launch.readOnly,
                returnUnchangedText = launch.returnUnchangedText,
                useTextFile = launch.useTextFile,
                showDebugSource = launch.showDebugSource,
                showLoginSource = launch.showLoginSource,
            )
        }

        override suspend fun read(sessionId: String) = sessions[sessionId]

        override suspend fun write(sessionId: String, session: CodeEditorSession): Boolean {
            if (failWrites || (failClose && session.finished)) error("disk failed")
            beforeWrite()
            val previous = sessions[sessionId]
            if (previous == session) return true
            if (previous?.finished == true || (previous?.revision ?: -1) >= session.revision)
                return false
            sessions[sessionId] = session
            return true
        }

        override suspend fun returnFile(sessionId: String, receiptId: String) =
            "$sessionId/$receiptId"

        override suspend fun prepareOutput(
            sessionId: String,
            session: CodeEditorSession,
        ): CodeEditorResultPayload {
            if (failOutput) error("output failed")
            if (sessions[sessionId] != session) throw CodeEditorSessionConflict()
            val receipt = session.returnReceipt!!
            return CodeEditorResultPayload(
                receipt.cursorPosition,
                text = session.text.takeIf { receipt.includeText && !session.useTextFile },
                textFile = receipt.textFile.takeIf { receipt.includeText && session.useTextFile },
                action = receipt.action,
            )
        }

        override suspend fun releaseOutput(path: String?) {
            released += path
        }
    }
}
