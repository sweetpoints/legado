package io.legado.app.ui.rss.source.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssSourceEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssSourceEditorViewModel>()
    private val repositories = mutableListOf<Fake>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        models.forEach {
            it.stop()
            it.viewModelScope.cancel()
        }
        repositories.forEach {
            it.readGate?.complete(Unit)
            it.saveGate?.complete(Unit)
            it.editorGate?.complete(Unit)
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        key: String? = "original",
    ) =
        RssSourceEditorViewModel(repo, saved, key).also {
            models += it
            repositories += repo
        }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun existingLoadsEveryFieldAndNewDefaultsRemainUnsavedUntilExplicitSave() =
        runTest(dispatcher) {
            val repo = Fake()
            val existing = model(repo)
            runCurrent()
            assertTrue(existing.state.value.loaded)
            assertEquals("original", existing.sourceUrl)
            assertEquals(
                ".next",
                existing.state.value.draft[RssSourceEditorField.NextContentUrl].text,
            )
            assertTrue(existing.state.value.draft.cookieJar)
            val new = model(repo, key = null)
            runCurrent()
            assertNull(new.sourceUrl)
            assertTrue(new.state.value.draft.enableJs)
            assertTrue(new.state.value.draft.loadWithBaseUrl)
            assertEquals(0, repo.saves)
            new.save(RssSourceEditorSaveAction.Close)
            assertEquals(RssSourceEditorIssue.Required, new.state.value.issue)
        }

    @Test
    fun loadFailureRetryBlocksTypingAndStoppedNonCooperativeFailureCannotPublish() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.failRead = true
            val vm = model(repo)
            runCurrent()
            assertFalse(vm.state.value.canEdit)
            vm.field(RssSourceEditorField.SourceName, RssSourceEditorText("lost"))
            assertEquals("", vm.state.value.draft[RssSourceEditorField.SourceName].text)
            repo.failRead = false
            vm.retry()
            runCurrent()
            assertEquals("Name", vm.state.value.draft[RssSourceEditorField.SourceName].text)
            val late = Fake()
            late.readGate = CompletableDeferred()
            late.failRead = true
            val stopped = model(late)
            runCurrent()
            stopped.stop()
            val before = stopped.state.value
            late.readGate!!.complete(Unit)
            runCurrent()
            assertEquals(before, stopped.state.value)
        }

    @Test
    fun missingExistingClosesWithoutSilentlyCreatingNewSource() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.missing = true
            val vm = model(repo)
            runCurrent()
            assertTrue(vm.state.value.missing)
            assertEquals(RssSourceEditorEffectKind.Close, vm.state.value.effects.single().kind)
            assertFalse(vm.state.value.canEdit)
            assertEquals(0, repo.saves)
        }

    @Test
    fun largeDraftAllCursorsOptionsTabsAndFocusRestoreWithSmallSavedState() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.field(
                RssSourceEditorField.StartHtml,
                RssSourceEditorText("body".repeat(250000), 80000, 90000),
            )
            vm.options {
                it.copy(
                    singleUrl = true,
                    preload = true,
                    showWebLog = true,
                    type = 2,
                    articleStyle = 4,
                )
            }
            vm.tab(1)
            vm.focus(RssSourceEditorField.StartHtml)
            vm.expanded(true)
            vm.autoComplete(true)
            runCurrent()
            vm.flush()
            vm.stop()
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertEquals(vm.state.value.draft, restored.state.value.draft)
            assertEquals(1, restored.state.value.tab)
            assertEquals(RssSourceEditorField.StartHtml, restored.state.value.focus)
            assertTrue(restored.state.value.expanded)
            assertTrue(restored.state.value.autoComplete)
            assertTrue(
                saved.keys().all {
                    (saved.get<Any?>(it) as? String)?.length?.let { it < 2000 } != false
                }
            )
        }

    @Test
    fun dirtyExitTracksPreviouslyOmittedImageLibraryAndUrlFiltersButNotCursor() =
        runTest(dispatcher) {
            val vm = model(Fake())
            runCurrent()
            vm.field(RssSourceEditorField.SourceName, RssSourceEditorText("Name", 4))
            vm.requestExit()
            assertEquals(RssSourceEditorEffectKind.Close, vm.state.value.effects.single().kind)
            vm.consume(vm.state.value.effects.single())
            listOf(
                    RssSourceEditorField.RuleImage,
                    RssSourceEditorField.JsLib,
                    RssSourceEditorField.ContentBlacklist,
                )
                .forEach { field ->
                    val edited = model(Fake())
                    runCurrent()
                    edited.field(field, RssSourceEditorText("changed"))
                    edited.requestExit()
                    assertTrue(edited.state.value.exit)
                    edited.keepEditing()
                    assertFalse(edited.state.value.exit)
                    edited.requestExit()
                    edited.discard()
                    assertEquals(
                        RssSourceEditorEffectKind.Close,
                        edited.state.value.effects.single().kind,
                    )
                }
        }

    @Test
    fun saveFailurePreservesDraftAndDebugLoginVariableAreDeliveredOnlyAfterAwaitedSave() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.field(RssSourceEditorField.SourceName, RssSourceEditorText("Changed"))
            repo.failSave = true
            vm.save(RssSourceEditorSaveAction.Debug)
            runCurrent()
            assertEquals("Changed", vm.state.value.draft[RssSourceEditorField.SourceName].text)
            assertEquals("save failed", vm.state.value.error)
            assertTrue(vm.state.value.effects.isEmpty())
            repo.failSave = false
            repo.saveGate = CompletableDeferred()
            vm.save(RssSourceEditorSaveAction.Debug)
            runCurrent()
            assertTrue(vm.state.value.busy)
            assertTrue(vm.state.value.effects.isEmpty())
            repo.saveGate!!.complete(Unit)
            runCurrent()
            val debug = vm.state.value.effects.single()
            assertEquals(RssSourceEditorEffectKind.SavedDebug, debug.kind)
            vm.consume(debug)
            vm.field(RssSourceEditorField.LoginUrl, RssSourceEditorText("@js:login"))
            vm.save(RssSourceEditorSaveAction.Login)
            runCurrent()
            val login = vm.state.value.effects.single()
            assertEquals(RssSourceEditorEffectKind.SavedLogin, login.kind)
            vm.consume(login)
            vm.save(RssSourceEditorSaveAction.Variable)
            runCurrent()
            assertEquals(
                RssSourceEditorEffectKind.SavedVariable,
                vm.state.value.effects.single().kind,
            )
            assertEquals(4, repo.saves)
        }

    @Test
    fun canceledDurableSaveRestoresOneDeliveryAndConsumedFinishedSnapshotNeverRepeatsCallback() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.saveGate = CompletableDeferred()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.field(RssSourceEditorField.SourceUrl, RssSourceEditorText("renamed"))
            vm.save(RssSourceEditorSaveAction.Close)
            runCurrent()
            vm.stop()
            repo.saveGate!!.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.effects.isEmpty())
            val restoredSaved = snapshot(saved)
            val restored = model(repo, restoredSaved)
            runCurrent()
            assertEquals("renamed", restored.sourceUrl)
            val effect = restored.state.value.effects.single()
            assertEquals(RssSourceEditorEffectKind.SavedClose, effect.kind)
            restored.consume(effect)
            runCurrent()
            restored.flush()
            restored.stop()
            val final = model(repo, snapshot(restoredSaved))
            runCurrent()
            assertTrue(final.state.value.finished)
            assertTrue(final.state.value.savedResult)
            assertTrue(final.state.value.effects.isEmpty())
            assertEquals(1, repo.saves)
        }

    @Test
    fun pasteObjectAndSingleArrayReplacesAllEditableFieldsButPreservesOriginalIdentityUntilSave() =
        runTest(dispatcher) {
            val vm = model(Fake())
            runCurrent()
            vm.tab(3)
            val foreign =
                RssSource(
                    "foreign",
                    "Pasted",
                    contentWhitelist = "allow",
                    ruleImage = "image",
                    jsLib = "library",
                    singleUrl = true,
                    type = 2,
                )
            vm.paste(GSON.toJson(foreign))
            runCurrent()
            assertEquals("original", vm.sourceUrl)
            assertEquals("foreign", vm.state.value.draft[RssSourceEditorField.SourceUrl].text)
            assertEquals("allow", vm.state.value.draft[RssSourceEditorField.ContentWhitelist].text)
            assertEquals(0, vm.state.value.tab)
            vm.paste(GSON.toJson(listOf(foreign)))
            runCurrent()
            assertEquals("library", vm.state.value.draft[RssSourceEditorField.JsLib].text)
            vm.paste("bad")
            runCurrent()
            assertEquals(RssSourceEditorIssue.Format, vm.state.value.issue)
            assertEquals("Pasted", vm.state.value.draft[RssSourceEditorField.SourceName].text)
        }

    @Test
    fun selectionInsertionUndoRedoAndCookieUseCurrentDraftAndVariableCallbackChecksSavedKey() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.focus(RssSourceEditorField.StartHtml)
            vm.field(RssSourceEditorField.StartHtml, RssSourceEditorText("abcdef", 2, 4))
            vm.insert("URL")
            assertEquals(
                RssSourceEditorText("abURLef", 5),
                vm.state.value.draft[RssSourceEditorField.StartHtml],
            )
            vm.undo()
            assertEquals("abcdef", vm.state.value.draft[RssSourceEditorField.StartHtml].text)
            vm.redo()
            assertEquals("abURLef", vm.state.value.draft[RssSourceEditorField.StartHtml].text)
            vm.field(RssSourceEditorField.SourceUrl, RssSourceEditorText("edited"))
            vm.clearCookie()
            runCurrent()
            assertEquals("edited", repo.cookie)
            vm.setVariable("edited", "wrong")
            vm.setVariable("original", "accepted")
            runCurrent()
            assertEquals("accepted", repo.variableValue)
        }

    @Test
    fun allTextFieldsUseFileEditorAndCursorOnlyReturnAndCancellationPreserveDraft() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.focus(RssSourceEditorField.SourceName)
            vm.openEditor()
            runCurrent()
            val effect = vm.state.value.effects.single()
            assertEquals(RssSourceEditorEffectKind.Editor, effect.kind)
            vm.consume(effect)
            vm.editorReturned(true, null, null, 3)
            runCurrent()
            assertEquals(
                RssSourceEditorText("Name", 3),
                vm.state.value.draft[RssSourceEditorField.SourceName],
            )
            assertFalse(vm.state.value.editorPending)
            vm.focus(RssSourceEditorField.NextContentUrl)
            vm.openEditor()
            runCurrent()
            vm.consume(vm.state.value.effects.single())
            vm.editorReturned(false, "ignored", null, 0)
            runCurrent()
            assertEquals(".next", vm.state.value.draft[RssSourceEditorField.NextContentUrl].text)
            assertFalse(vm.state.value.editorPending)
        }

    @Test
    fun canceledNativeReturnWritesDiskBeforeFileCleanupAndRestoresWithoutReplay() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.focus(RssSourceEditorField.StartJs)
            vm.openEditor()
            runCurrent()
            vm.consume(vm.state.value.effects.single())
            repo.files["output"] = "returned"
            repo.editorGate = CompletableDeferred()
            vm.editorReturned(true, null, "output", 5)
            runCurrent()
            vm.stop()
            val before = snapshot(saved)
            repo.editorGate!!.complete(Unit)
            runCurrent()
            assertFalse(repo.files.containsKey("output"))
            assertEquals(
                "returned",
                repo.documents.values.single().draft[RssSourceEditorField.StartJs].text,
            )
            val restored = model(repo, before)
            runCurrent()
            assertEquals(
                RssSourceEditorText("returned", 5),
                restored.state.value.draft[RssSourceEditorField.StartJs],
            )
            assertFalse(restored.state.value.editorPending)
            assertEquals(1, restored.state.value.tab)
            assertTrue(restored.state.value.effects.isEmpty())
        }

    @Test
    fun nativeReturnReadFailureKeepsOutputAndPendingDraftForExplicitRetryOrDiscard() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.focus(RssSourceEditorField.StartJs)
            vm.openEditor()
            runCurrent()
            vm.consume(vm.state.value.effects.single())
            repo.files["output"] = "returned"
            repo.failEditor = true
            vm.editorReturned(true, null, "output", 4)
            runCurrent()
            assertEquals("read failed", vm.state.value.error)
            assertTrue(vm.state.value.editorPending)
            assertTrue(repo.files.containsKey("output"))
            repo.failEditor = false
            vm.retryEditorResult()
            runCurrent()
            assertEquals("returned", vm.state.value.draft[RssSourceEditorField.StartJs].text)
            assertFalse(repo.files.containsKey("output"))
        }

    private class Fake : RssSourceEditorRepository {
        var missing = false
        var failRead = false
        var failSave = false
        var failEditor = false
        var saves = 0
        var readGate: CompletableDeferred<Unit>? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var editorGate: CompletableDeferred<Unit>? = null
        val documents = mutableMapOf<String, RssSourceEditorDocument>()
        val files = mutableMapOf<String, String>()
        var cookie: String? = null
        var variableValue: String? = null

        override suspend fun load(key: String) =
            if (missing) null
            else
                RssSourceEditorDocument(
                    key,
                    RssSourceEditorDraft.from(RssSource(key, "Name", nextContentUrl = ".next")),
                    customOrder = 7,
                )

        override suspend fun readDraft(session: String): RssSourceEditorDocument? {
            readGate?.let { withContext(NonCancellable) { it.await() } }
            if (failRead) error("read failed")
            return documents[session]
        }

        override suspend fun writeDraft(session: String, document: RssSourceEditorDocument) {
            if ((documents[session]?.revision ?: -1) <= document.revision)
                documents[session] = document
        }

        override suspend fun save(
            session: String,
            document: RssSourceEditorDocument,
            action: RssSourceEditorSaveAction,
            autoComplete: Boolean,
        ): RssSourceEditorDocument =
            withContext(NonCancellable) {
                saves++
                if (failSave) error("save failed")
                val source = document.draft.entity(autoComplete = autoComplete)
                val draft = RssSourceEditorDraft.from(source)
                val result =
                    document.copy(
                        originalKey = source.sourceUrl,
                        draft = draft,
                        baseline = draft,
                        revision = document.revision + 1,
                        delivery =
                            RssSourceEditorDelivery(
                                UUID.randomUUID().toString(),
                                action,
                                source.sourceUrl,
                                !source.loginUrl.isNullOrBlank(),
                            ),
                    )
                writeDraft(session, result)
                saveGate?.await()
                result
            }

        override suspend fun parse(text: String) =
            (GSON.fromJsonObject<RssSource>(text).getOrNull()
                    ?: GSON.fromJsonArray<RssSource>(text).getOrNull()?.singleOrNull())
                ?.let(RssSourceEditorDraft::from)

        override suspend fun export(document: RssSourceEditorDocument, autoComplete: Boolean) =
            GSON.toJson(document.draft.entity(autoComplete = autoComplete))

        override suspend fun clearCookie(url: String) {
            cookie = url
        }

        override suspend fun variable(key: String) = variableValue

        override suspend fun setVariable(key: String, value: String?) {
            variableValue = value
        }

        override suspend fun editorInput(text: String) =
            UUID.randomUUID().toString().also { files[it] = text }

        override suspend fun editorText(path: String): String {
            editorGate?.await()
            if (failEditor) error("read failed")
            return files.getValue(path)
        }

        override suspend fun clearEditor(vararg paths: String?) {
            paths.forEach { files.remove(it) }
        }
    }
}
