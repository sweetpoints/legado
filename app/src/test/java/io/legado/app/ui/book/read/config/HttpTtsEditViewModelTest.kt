package io.legado.app.ui.book.read.config

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpTtsEditViewModelTest {
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
    fun existingNullableFieldsOpenCleanAndUnchangedExitDoesNotConfirm() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            assertEquals("Stored", model.state.value.draft.name)
            model.requestExit()
            assertTrue(model.state.value.finished)
            assertFalse(model.state.value.exit)
            assertTrue(repo.saves.isEmpty())
        }

    @Test
    fun draftAndCookieRestoreWithoutSavingAndDiscardOnlyCloses() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = HttpTtsEditViewModel(repo, saved, 7)
            runCurrent()
            model.edit(HttpTtsEditorField.JsLib, "function sign() {}", 9, 12)
            model.cookie(true)
            model.requestExit()
            val restored = HttpTtsEditViewModel(repo, snapshot(saved), 7)
            runCurrent()
            assertEquals("function sign() {}", restored.state.value.draft.jsLib)
            assertTrue(restored.state.value.draft.cookie)
            assertEquals(
                HttpTtsEditorSelection(9, 12),
                restored.state.value.selections[HttpTtsEditorField.JsLib],
            )
            assertTrue(restored.state.value.exit)
            restored.keepEditing()
            assertFalse(restored.state.value.exit)
            restored.requestExit()
            restored.discard()
            assertTrue(restored.state.value.finished)
            assertTrue(repo.saves.isEmpty())
        }

    @Test
    fun codeResultTargetsOriginalFieldAfterRestorationAndAllowsEndCursor() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = HttpTtsEditViewModel(repo, saved, 7)
            runCurrent()
            model.edit(HttpTtsEditorField.Url, "original", 3, 5)
            model.focus(HttpTtsEditorField.Url)
            model.fullEdit()
            val event = model.state.value.pending.single()
            assertEquals(HttpTtsEditorField.Url, event.field)
            assertEquals(3, event.cursor)
            model.consume(event.id)
            model.focus(HttpTtsEditorField.Name)
            val restored = HttpTtsEditViewModel(repo, snapshot(saved), 7)
            runCurrent()
            restored.codeResult("new url", 7)
            assertEquals("new url", restored.state.value.draft.url)
            assertEquals("Stored", restored.state.value.draft.name)
            assertEquals(
                HttpTtsEditorSelection(7, 7),
                restored.state.value.selections[HttpTtsEditorField.Url],
            )
            assertEquals(HttpTtsEditorField.Url, restored.state.value.focus)
        }

    @Test
    fun cancelledCodeEditChangesNothingAndAllowsNextRequest() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.focus(HttpTtsEditorField.Url)
            model.fullEdit()
            model.fullEdit()
            assertEquals(1, model.state.value.pending.size)
            model.consume(model.state.value.pending.single().id)
            model.codeCancelled()
            model.fullEdit()
            assertEquals(1, model.state.value.pending.size)
            assertEquals(repo.original.url, model.state.value.draft.url)
        }

    @Test
    fun saveCompletesBeforeRebuildAndLoginAndDuplicateSaveIsBlocked() =
        runTest(dispatcher) {
            val repo =
                Fake().apply {
                    saveGate = CompletableDeferred()
                    rebuild = true
                }
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.edit(HttpTtsEditorField.LoginUrl, "<js>login()</js>", 0, 0)
            model.save(true)
            model.save(true)
            runCurrent()
            assertTrue(model.state.value.busy)
            assertTrue(model.state.value.pending.isEmpty())
            assertEquals(1, repo.saves.size)
            repo.saveGate!!.complete(Unit)
            runCurrent()
            assertEquals(
                listOf(HttpTtsEditorAction.Rebuild, HttpTtsEditorAction.Login),
                model.state.value.pending.map { it.action },
            )
            assertEquals("7", model.state.value.pending.last().text)
            assertFalse(model.state.value.finished)
            model.consume(model.state.value.pending.first().id)
            model.consume(model.state.value.pending.single().id)
            model.requestExit()
            assertTrue(model.state.value.finished)
            assertFalse(model.state.value.exit)
        }

    @Test
    fun blankLoginDoesNotSaveButNormalSaveCloses() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.save(true)
            runCurrent()
            assertTrue(repo.saves.isEmpty())
            assertEquals("登录url不能为空", model.state.value.error)
            model.save(false)
            runCurrent()
            assertTrue(model.state.value.finished)
            assertEquals(HttpTtsEditorAction.Saved, model.state.value.pending.single().action)
        }

    @Test
    fun loadingBlocksEditsAndSavingUntilOriginalArrives() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadGate = CompletableDeferred() }
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.edit(HttpTtsEditorField.Name, "early", 0, 0)
            model.save(false)
            assertTrue(repo.saves.isEmpty())
            repo.loadGate!!.complete(Unit)
            runCurrent()
            assertEquals("Stored", model.state.value.draft.name)
        }

    @Test
    fun latePasteDoesNotOverwriteLaterTypingAndPasteRetainsEditorId() =
        runTest(dispatcher) {
            val repo = Fake().apply { parseGate = CompletableDeferred() }
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.paste("json")
            runCurrent()
            model.edit(HttpTtsEditorField.Name, "typed", 5, 5)
            repo.parseGate!!.complete(Unit)
            runCurrent()
            assertEquals("typed", model.state.value.draft.name)
            repo.parseGate = null
            model.paste("json")
            runCurrent()
            assertEquals("Imported", model.state.value.draft.name)
            assertEquals(7L, model.state.value.draft.id)
            assertEquals("lib", model.state.value.draft.jsLib)
            assertTrue(model.state.value.draft.cookie)
        }

    @Test
    fun emptyHeaderIsVisibleAndDeletionUsesCurrentDraft() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.showHeader()
            runCurrent()
            assertEquals("", model.state.value.header)
            model.closeHeader()
            assertNull(model.state.value.header)
            model.deleteHeader()
            runCurrent()
            assertEquals(7L, repo.headerDeleted?.id)
        }

    @Test
    fun pauseRangeAndInvalidValueNormalizeOnlyWhenSerializing() {
        val draft = HttpTtsEditorDraft(7, pause = "999999", jsLib = "lib", cookie = true)
        assertEquals(10000, draft.entity().pauseDuration)
        assertEquals("999999", draft.pause)
        assertEquals(0, draft.copy(pause = "-").entity().pauseDuration)
        assertEquals(0, draft.copy(pause = "-2").entity().pauseDuration)
        assertEquals("lib", draft.entity().jsLib)
        assertTrue(draft.entity().enabledCookieJar == true)
    }

    @Test
    fun newUrlOnlyAndCookieOnlyDraftsRequireDiscardConfirmation() =
        runTest(dispatcher) {
            val repo = Fake()
            val url = HttpTtsEditViewModel(repo, SavedStateHandle())
            url.edit(HttpTtsEditorField.Url, "url", 3, 3)
            url.requestExit()
            assertTrue(url.state.value.exit)
            assertFalse(url.state.value.finished)
            val cookie = HttpTtsEditViewModel(repo, SavedStateHandle())
            cookie.cookie(true)
            cookie.requestExit()
            assertTrue(cookie.state.value.exit)
            assertFalse(cookie.state.value.finished)
            assertTrue(repo.saves.isEmpty())
        }

    @Test
    fun failedLoadBlocksWritesAndRestorationRetriesOriginalMetadata() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadError = IllegalStateException("read failed") }
            val saved = SavedStateHandle()
            val model = HttpTtsEditViewModel(repo, saved, 7)
            runCurrent()
            assertTrue(model.state.value.loadFailed)
            model.save(false)
            model.save(true)
            runCurrent()
            assertTrue(repo.saves.isEmpty())
            repo.loadError = null
            val restored = HttpTtsEditViewModel(repo, snapshot(saved), 7)
            runCurrent()
            assertFalse(restored.state.value.loadFailed)
            assertEquals(repo.original, restored.state.value.draft)
            restored.save(false)
            runCurrent()
            assertEquals(repo.original, repo.saves.single())
        }

    @Test
    fun explicitRetryLoadsMetadataWithoutSubmittingBlankRow() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadError = IllegalStateException("read failed") }
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            repo.loadError = null
            model.retryLoad()
            runCurrent()
            assertFalse(model.state.value.loadFailed)
            assertEquals(repo.original, model.state.value.draft)
            assertTrue(repo.saves.isEmpty())
        }

    @Test
    fun discardCancelsQueuedPlatformActionsWithoutSaving() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = HttpTtsEditViewModel(repo, SavedStateHandle(), 7)
            runCurrent()
            model.edit(HttpTtsEditorField.Name, "changed", 0, 0)
            model.request(HttpTtsEditorAction.Paste)
            model.focus(HttpTtsEditorField.Url)
            model.fullEdit()
            assertEquals(2, model.state.value.pending.size)
            model.discard()
            assertTrue(model.state.value.pending.isEmpty())
            assertTrue(repo.saves.isEmpty())
            model.request(HttpTtsEditorAction.Help)
            assertTrue(model.state.value.pending.isEmpty())
        }

    @Test
    fun queuedActionsRestoreAndConsumedEventsStayConsumed() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = HttpTtsEditViewModel(repo, saved, 7)
            runCurrent()
            model.request(HttpTtsEditorAction.Help)
            model.request(HttpTtsEditorAction.Log)
            model.consume(model.state.value.pending.first().id)
            val restored = HttpTtsEditViewModel(repo, snapshot(saved), 7)
            runCurrent()
            assertEquals(HttpTtsEditorAction.Log, restored.state.value.pending.single().action)
        }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Fake : HttpTtsEditorRepository {
        val original = HttpTtsEditorDraft(7, name = "Stored", url = "url", pause = "0")
        var loadGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var parseGate: CompletableDeferred<Unit>? = null
        var loadError: Exception? = null
        var rebuild = false
        val saves = mutableListOf<HttpTtsEditorDraft>()
        var headerDeleted: HttpTtsEditorDraft? = null

        override suspend fun load(id: Long): HttpTtsEditorDraft {
            loadGate?.await()
            loadError?.let { throw it }
            return original
        }

        override suspend fun save(
            original: HttpTtsEditorDraft?,
            draft: HttpTtsEditorDraft,
        ): Boolean {
            saves += draft
            saveGate?.await()
            return rebuild
        }

        override suspend fun parse(text: String, id: Long): HttpTtsEditorDraft {
            parseGate?.await()
            return HttpTtsEditorDraft(id, name = "Imported", jsLib = "lib", cookie = true)
        }

        override suspend fun copy(draft: HttpTtsEditorDraft) = "json"

        override suspend fun loginHeader(draft: HttpTtsEditorDraft): String? = null

        override suspend fun deleteLoginHeader(draft: HttpTtsEditorDraft) {
            headerDeleted = draft
        }
    }
}
