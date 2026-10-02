package io.legado.app.ui.autoTask

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.legado.app.R
import io.legado.app.data.entities.AutoTaskRule
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AutoTaskEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<AutoTaskEditorViewModel>()
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle(), id: String? = null): AutoTaskEditorViewModel {
        lateinit var model: AutoTaskEditorViewModel; compose.runOnIdle { model = AutoTaskEditorViewModel(repo, saved, id); models += model }; return model
    }
    @After fun after() { compose.runOnIdle { models.forEach { it.stop(); it.viewModelScope.cancel() } } }
    private fun label(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)
    private fun attach(model: AutoTaskEditorViewModel, event: (AutoTaskEditorEffect, String?) -> Unit = { _, _ -> }, close: (Boolean) -> Unit = {}) {
        compose.setContent { LegadoComposeTheme { Box(Modifier.height(520.dp)) { AutoTaskEditorRoute(model, event, close, { throw AssertionError(it) }) } } }
        compose.waitUntil { !model.state.value.loading }
    }
    private fun navigate(field: AutoTaskEditorField) {
        compose.onNodeWithTag("task-editor-navigation").performClick()
        compose.onNodeWithTag("task-editor-navigate-" + field.name).performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithTag("task-editor-" + field.name).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun allFieldNavigationIncludesMultilineLoginUrlAndRetainsReturnedCursor() {
        val repo = Fake(); val model = model(repo); var launched: AutoTaskEditorEffect? = null
        attach(model, { effect, _ -> launched = effect })
        navigate(AutoTaskEditorField.LoginUrl)
        val body = "line1\nline2"
        compose.onNodeWithTag("task-editor-LoginUrl").performTextReplacement(body)
        compose.onNodeWithTag("task-editor-LoginUrl").performTextInputSelection(androidx.compose.ui.text.TextRange(4, 6))
        compose.onNodeWithTag("task-editor-fullscreen").performClick()
        compose.waitUntil { launched != null }; assertEquals(AutoTaskEditorField.LoginUrl, launched!!.field); assertEquals(4, launched!!.cursor)
        compose.runOnIdle { model.editorReturned(true, "returned\nurl", null, 7) }
        compose.waitUntil { !model.state.value.editorPending }
        compose.onNodeWithTag("task-editor-LoginUrl").assertTextEquals("returned\nurl")
        assertEquals(7, model.state.value.draft[AutoTaskEditorField.LoginUrl].start)
    }
    @Test fun validationShowsLocalizedErrorsAndSaveFailureRetainsCurrentBody() {
        val repo = Fake(); val model = model(repo); attach(model)
        compose.onNodeWithTag("task-editor-save").performClick(); compose.onNodeWithText(label(R.string.auto_task_name_required)).assertExists()
        navigate(AutoTaskEditorField.Name); compose.onNodeWithTag("task-editor-Name").performTextReplacement("Task")
        navigate(AutoTaskEditorField.Cron); compose.onNodeWithTag("task-editor-Cron").performTextReplacement("bad")
        compose.onNodeWithTag("task-editor-save").performClick(); compose.onNodeWithText(label(R.string.auto_task_cron_invalid)).assertExists()
        compose.onNodeWithTag("task-editor-Cron").performTextReplacement("0 * * * *")
        compose.onNodeWithTag("task-editor-save").performClick(); compose.onNodeWithText(label(R.string.auto_task_script_empty)).assertExists()
        navigate(AutoTaskEditorField.Script); compose.onNodeWithTag("task-editor-Script").performTextReplacement("42")
        compose.runOnIdle { repo.failSave = true }; compose.onNodeWithTag("task-editor-save").performClick()
        compose.waitUntil { !model.state.value.busy && model.state.value.error != null }
        compose.onNodeWithTag("task-editor-Script").assertTextEquals("42"); assertEquals(0, repo.saves)
    }
    @Test fun actualBackConfirmationKeepOrDiscardNeverWritesTaskAndRestoresExitDraft() {
        val repo = Fake(); val saved = SavedStateHandle(); val initial = model(repo, saved, "old")
        var active by mutableStateOf(initial); var closes = 0
        compose.setContent { LegadoComposeTheme { AutoTaskEditorRoute(active, { _, _ -> }, { assertFalse(it); closes++ }, { throw AssertionError(it) }) } }
        compose.waitUntil { !initial.state.value.loading }; navigate(AutoTaskEditorField.Script)
        compose.onNodeWithTag("task-editor-Script").performTextReplacement("unsaved")
        compose.onNodeWithTag("task-editor-back").performClick(); compose.onNodeWithText(label(R.string.exit_no_save)).assertExists()
        compose.runOnIdle { initial.stop(); initial.viewModelScope.cancel(); active = AutoTaskEditorViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), "old"); models += active }
        compose.waitUntil { !active.state.value.loading }; compose.onNodeWithTag("task-editor-keep").performClick()
        assertEquals("unsaved", active.state.value.draft[AutoTaskEditorField.Script].text)
        compose.onNodeWithTag("task-editor-back").performClick(); compose.onNodeWithTag("task-editor-discard").performClick()
        compose.waitUntil { closes == 1 }; assertEquals(0, repo.saves)
    }
    @Test fun copyAndPasteMenuUseOriginalTaskIdAndFullDraftWithoutSaving() {
        val repo = Fake(); val model = model(repo, id = "old"); var copied: String? = null
        attach(model, { effect, payload -> when (effect.kind) {
            AutoTaskEditorEffectKind.Clipboard -> copied = payload
            AutoTaskEditorEffectKind.Paste -> model.paste(GSON.toJson(AutoTaskRule("foreign", "Pasted", script = "42", header = "headers", jsLib = "lib", loginUi = "ui")))
            else -> Unit
        } })
        compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(label(R.string.paste_rule)).performClick()
        compose.waitUntil { model.state.value.draft[AutoTaskEditorField.Name].text == "Pasted" }
        compose.onNodeWithTag("task-editor-menu").performClick(); compose.onNodeWithText(label(R.string.copy_rule)).performClick()
        compose.waitUntil { copied != null }; val rule = GSON.fromJsonArray<AutoTaskRule>(copied!!).getOrThrow().single()
        assertEquals("old", rule.id); assertEquals("headers", rule.header); assertEquals("lib", rule.jsLib); assertEquals("ui", rule.loginUi); assertEquals(0, repo.saves)
    }
    @Test fun saveCloseWaitsForResumedConsumesBeforeCloseAndFinishedRestoreOnlyCloses() {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved, "old"); val owner = Owner()
        var active by mutableStateOf(model); var closes = 0; var nativeEvents = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
            AutoTaskEditorRoute(active, { _, _ -> nativeEvents++ }, { assertTrue(it); assertTrue(active.state.value.effects.isEmpty()); closes++ }, { throw AssertionError(it) })
        } } }
        compose.waitUntil { !model.state.value.loading }; compose.runOnIdle { model.save(AutoTaskEditorSaveAction.Close) }
        compose.waitUntil { model.state.value.effects.isNotEmpty() }; assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { closes == 1 }
        compose.runOnIdle { model.stop(); model.viewModelScope.cancel(); active = AutoTaskEditorViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }), "old"); models += active }
        compose.waitUntil { closes == 2 }; assertEquals(0, nativeEvents); assertEquals(1, repo.saves)
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class Fake : AutoTaskEditorRepository {
        var loaded: AutoTaskEditorDraft? = AutoTaskEditorDraft.from(AutoTaskRule("old", "Loaded", false, "0 * * * *", script = "loaded script", header = "header", jsLib = "lib", enabledCookieJar = false))
        val loadedIds = mutableListOf<String>(); val drafts = mutableMapOf<String, AutoTaskEditorDocument>(); val files = mutableMapOf<String, String>()
        var saves = 0; var failLoad = false; var failSave = false; var failRead = false
        var loadGate: CompletableDeferred<Unit>? = null; var saveGate: CompletableDeferred<Unit>? = null; var readGate: CompletableDeferred<Unit>? = null
        override suspend fun load(id: String): AutoTaskEditorDraft? { loadedIds += id; loadGate?.await(); if (failLoad) error("load failed"); return loaded }
        override suspend fun readDraft(session: String) = drafts[session]
        override suspend fun writeDraft(session: String, document: AutoTaskEditorDocument) { if (document.revision >= (drafts[session]?.revision ?: -1)) drafts[session] = document }
        override suspend fun save(session: String, document: AutoTaskEditorDocument, action: AutoTaskEditorSaveAction): AutoTaskEditorDocument = withContext(NonCancellable) {
            saveGate?.await(); if (failSave) error("save failed"); saves++
            document.copy(existing = true, baseline = document.draft, revision = document.revision + 1,
                delivery = AutoTaskEditorDelivery("save-$saves", action, document.draft[AutoTaskEditorField.LoginUrl].text.isNotEmpty())).also { writeDraft(session, it) }
        }
        override suspend fun parse(text: String) = (GSON.fromJsonObject<AutoTaskRule>(text).getOrNull() ?: GSON.fromJsonArray<AutoTaskRule>(text).getOrNull()?.singleOrNull())?.let(AutoTaskEditorDraft::from)
        override suspend fun export(id: String, draft: AutoTaskEditorDraft) = GSON.toJson(listOf(draft.entity(id)))
        override suspend fun editorInput(text: String) = "input".also { files[it] = text }
        override suspend fun editorText(path: String): String { readGate?.await(); if (failRead) error("read failed"); return files[path] ?: error("missing output") }
        override suspend fun clearEditor(vararg paths: String?) { paths.forEach { files.remove(it) } }
    }
}
