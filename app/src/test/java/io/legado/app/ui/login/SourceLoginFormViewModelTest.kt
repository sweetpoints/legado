package io.legado.app.ui.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.rule.FlexChildStyle
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.repository.*
import io.legado.app.model.login.LoginUiV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceLoginFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    @Test fun legacyFieldsDefaultsAndHiddenValuesSurviveRestorationWithoutPersisting() = managedTest {
        val repo = Fake(false); val saved = SavedStateHandle(); val model = createModel(repo, saved, { 1000 }); runCurrent()
        model.edit("name", "typed"); model.upUiData(mapOf("hidden" to "token")); runCurrent()
        val restored = createModel(repo, snapshot(saved), { 1000 }); runCurrent()
        assertEquals("typed", restored.state.value.values["name"]); assertEquals("token", restored.state.value.values["hidden"])
        assertEquals(1, repo.renders); assertTrue(repo.persisted.isEmpty()); assertEquals("A", restored.state.value.values["select"])
        restored.close(); runCurrent(); assertTrue(restored.state.value.finished); assertEquals("typed", repo.persisted.single()["name"])
    }
    @Test fun clearConfirmationCancellationDoesNotDeleteAndConfirmedClearCannotResaveDraft() = managedTest {
        val repo = Fake(false); val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        model.edit("name", "draft"); model.requestClear(true); model.requestClear(false); assertEquals(0, repo.clears)
        model.requestClear(true); model.clear(); runCurrent(); assertEquals(1, repo.clears)
        assertTrue(model.state.value.values.isEmpty()); model.close(); assertTrue(repo.persisted.isEmpty())
    }
    @Test fun initialRenderFailureLeavesMenusUsableAndRefreshCanRecover() = managedTest {
        val repo = Fake(false).apply { renderError = IllegalStateException("script failed") }
        val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        assertFalse(model.state.value.rendered); assertFalse(model.state.value.loading)
        model.showHeader(); runCurrent(); assertEquals("", model.state.value.header)
        model.log(); assertEquals(SourceLoginFormAction.Log, model.state.value.pending.single().action)
        repo.renderError = null; model.reUiView(false); runCurrent(); assertTrue(model.state.value.rendered)
    }
    @Test fun failedV2StateRenderPreservesOldRowsAndCommittedState() = managedTest {
        val repo = Fake(true); val saved = SavedStateHandle(); val model = createModel(repo, saved, { 1000 }); runCurrent()
        val original = model.state.value.rows; repo.command = LoginUiV2.ActionResult(stateJson = "{\"step\":2}", error = mapOf("name" to "required"))
        repo.renderError = IllegalStateException("render failed"); model.action(button(model), false); runCurrent()
        assertEquals(original, model.state.value.rows); assertEquals("{}", saved.get<String>("loginForm.state"))
        assertFalse(model.state.value.busy); assertEquals("required", model.state.value.errors["name"])
    }
    @Test fun v2FieldPriorityIsRenderThenCurrentSessionThenStoredAndNoImplicitSave() = managedTest {
        val repo = Fake(true).apply { stored = mapOf("name" to "stored") }; val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        assertEquals("stored", model.state.value.values["name"]); model.edit("name", "session")
        model.render(); runCurrent(); assertEquals("session", model.state.value.values["name"])
        repo.rows = repo.rows.map { if (it.key == "name") it.copy(value = "rendered") else it }
        model.render(); runCurrent(); assertEquals("rendered", model.state.value.values["name"])
        model.close(); runCurrent(); assertTrue(repo.persisted.isEmpty()); assertTrue(repo.storedJson.isEmpty())
    }
    @Test fun failedV2LoginPersistencePreventsCloseAndReenablesActions() = managedTest {
        val repo = Fake(true).apply { command = LoginUiV2.ActionResult(loginJson = "{\"name\":\"user\"}", close = true); saveResult = false }
        val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent(); model.action(button(model), false); runCurrent()
        assertFalse(model.state.value.finished); assertFalse(model.state.value.busy); assertNotNull(model.state.value.error)
        assertEquals(1, repo.storedJson.size)
        repo.saveResult = true; model.action(button(model), false); runCurrent(); assertTrue(model.state.value.finished)
    }
    @Test fun validationErrorsPreventCountdownAndDispatchCapturesOnlyCurrentV2Fields() = managedTest {
        val repo = Fake(true).apply { command = LoginUiV2.ActionResult(error = mapOf("name" to "required")) }
        val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent(); model.edit("name", "form")
        model.action(button(model), false); runCurrent(); assertEquals("required", model.state.value.errors["name"])
        assertTrue(model.state.value.countdowns.isEmpty()); assertEquals("form", repo.actionForms.single()["name"])
        assertFalse(repo.actionForms.single().containsKey("button"))
    }
    @Test fun countdownRestoresRemainingDeadlineAndBlocksRepeatedActions() = managedTest {
        var clock = 1000L; val repo = Fake(true); val saved = SavedStateHandle(); val model = createModel(repo, saved, { clock }); runCurrent()
        model.action(button(model), false); runCurrent(); assertEquals(3, model.state.value.countdowns["submit"])
        model.action(button(model), false); runCurrent(); assertEquals(1, repo.actionForms.size)
        clock = 2200; val restored = createModel(repo, snapshot(saved), { clock }); runCurrent()
        assertEquals(2, restored.state.value.countdowns["submit"])
        clock = 5000; advanceTimeBy(1000); runCurrent(); assertTrue(restored.state.value.countdowns.isEmpty())
        restored.action(button(restored), false); runCurrent(); assertEquals(2, repo.actionForms.size)
    }
    @Test fun textActionsDebounceAt600msAndCaptureLatestValue() = managedTest {
        val repo = Fake(false).apply { rows = rows.map { if (it.key == "name") it.copy(action = "onEdit()") else it } }
        val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        model.edit("name", "a"); advanceTimeBy(400); model.edit("name", "ab"); advanceTimeBy(599); runCurrent(); assertTrue(model.state.value.pending.isEmpty())
        advanceTimeBy(1); runCurrent(); val event = model.state.value.pending.single()
        assertEquals("onEdit()", event.text); assertEquals("ab", event.values["name"])
    }
    @Test fun legacyLongPressAndThrottleAreDistinctAndUrlUsesHostEvent() = managedTest {
        var clock = 1000L; val repo = Fake(false); val model = createModel(repo, SavedStateHandle(), { clock }); runCurrent()
        model.action(button(model), true); model.action(button(model), false); assertEquals(1, model.state.value.pending.size)
        assertTrue(model.state.value.pending.single().long)
        clock += 200; model.action(button(model).copy(action = "https://example.com"), false)
        assertEquals(SourceLoginFormAction.OpenUrl, model.state.value.pending.last().action)
    }
    @Test fun loginFailureStaysOpenAndDismissDoesNotImplicitlyResaveAfterSubmission() = managedTest {
        val repo = Fake(false).apply { loginError = IllegalStateException("login failed") }; val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        model.edit("name", "typed"); model.submit(); val event = model.state.value.pending.single(); model.consume(event.id); model.login(event, Any()); runCurrent()
        assertFalse(model.state.value.finished); assertFalse(model.state.value.busy); assertNotNull(model.state.value.error)
        model.close(); runCurrent(); assertTrue(model.state.value.finished); assertTrue(repo.persisted.isEmpty())
    }
    @Test fun callbacksResetVisibleDefaultsAndPreserveUnknownKeysOnlyForDeltaData() = managedTest {
        val repo = Fake(false); val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        model.upUiData(mapOf("name" to "updated", "button" to "new label", "extra" to 8)); runCurrent()
        assertEquals("updated", model.state.value.values["name"]); assertEquals("new label", button(model).label); assertEquals("8", model.state.value.values["extra"])
        model.upUiData(null); runCurrent(); assertEquals("default", model.state.value.values["name"])
        assertEquals("Button", button(model).label); assertFalse(model.state.value.values.containsKey("extra"))
    }
    @Test fun headerCopyIsQueuedAndEmptyHeaderHasExplicitState() = managedTest {
        val repo = Fake(false); val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        model.showHeader(); runCurrent(); assertEquals("", model.state.value.header); model.copyHeader(); assertTrue(model.state.value.pending.isEmpty())
        repo.headerValue = "Cookie: session"; model.showHeader(); runCurrent(); model.copyHeader()
        assertEquals(SourceLoginFormAction.Copy, model.state.value.pending.single().action); assertEquals("Cookie: session", model.state.value.pending.single().text)
        model.deleteHeader(); runCurrent(); assertEquals(1, repo.headerDeletes)
    }
    @Test fun deltaRefreshReordersReusesExistingPresentationAndAddsAndRemovesRows() = managedTest {
        val repo = Fake(false); val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        model.edit("name", "typed")
        val oldButton = button(model)
        repo.rows = listOf(oldButton.copy(label = "changed", action = "newAction", default = "new default"),
            SourceLoginRow("New", RowUi.Type.text, "new", default = "fresh"), repo.rows.first())
        model.reUiView(true); runCurrent()
        assertEquals(listOf("Button", "New", "Name"), model.state.value.rows.map { it.name })
        assertEquals("Button", button(model).label); assertEquals("submit", button(model).action)
        assertEquals("new default", button(model).default); assertEquals("typed", model.state.value.values["name"])
        assertEquals("fresh", model.state.value.values["new"])
        model.reUiView(false); runCurrent(); assertEquals("changed", button(model).label); assertEquals("newAction", button(model).action)
    }
    @Test fun lateUncancellableRenderCannotChangeFinishedRows() = managedTest {
        val repo = Fake(false); val model = createModel(repo, SavedStateHandle(), { 1000 }); runCurrent()
        val original = model.state.value.rows
        repo.renderGate = CompletableDeferred(); repo.ignoreCancellation = true; repo.rows = emptyList()
        model.render(); runCurrent(); model.close(); runCurrent(); assertTrue(model.state.value.finished)
        repo.renderGate!!.complete(Unit); runCurrent(); assertEquals(original, model.state.value.rows)
        model.fail(IllegalStateException("late")); assertNull(model.state.value.error)
    }
    @Test fun failedInitializationCanRetryAndRestorationWaitsWithoutLosingDraft() = managedTest {
        val repo = Fake(true).apply { readyError = IllegalStateException("load failed") }; val saved = SavedStateHandle()
        val model = createModel(repo, saved, { 1000 }); runCurrent()
        assertFalse(model.state.value.loading); assertFalse(model.state.value.rendered); assertNotNull(model.state.value.error)
        repo.readyError = null; model.retry(); runCurrent(); assertTrue(model.state.value.rendered)
        model.edit("name", "unconfirmed")
        repo.readyGate = CompletableDeferred()
        val restored = createModel(repo, snapshot(saved), { 1000 }); runCurrent()
        assertTrue(restored.state.value.loading); assertEquals("unconfirmed", restored.state.value.values["name"])
        repo.readyGate!!.complete(Unit); runCurrent()
        assertFalse(restored.state.value.loading); assertEquals("unconfirmed", restored.state.value.values["name"])
        assertTrue(repo.persisted.isEmpty()); assertTrue(repo.storedJson.isEmpty())
    }
    @Test fun flexWrapBasisAndGrowRespectSourceStyles() {
        val styles = listOf(FlexChildStyle(layout_flexGrow = 1f), FlexChildStyle(layout_flexGrow = 1f),
            FlexChildStyle(layout_flexBasisPercent = 0.5f, layout_wrapBefore = true))
        assertEquals(listOf(listOf(0 to 100, 1 to 100), listOf(2 to 100)), sourceLoginFlexLines(200, listOf(50, 50, 30), styles))
        assertEquals(listOf(listOf(0 to 200)), sourceLoginFlexLines(200, listOf(220), listOf(FlexChildStyle())))
        assertEquals(listOf(listOf(0 to 220)), sourceLoginFlexLines(200, listOf(220), listOf(FlexChildStyle(layout_flexShrink = 0f))))
    }
    private val stores = mutableListOf<ViewModelStore>()
    private fun createModel(repo: SourceLoginFormRepository, saved: SavedStateHandle, now: () -> Long): SourceLoginFormViewModel {
        val model = SourceLoginFormViewModel(repo, saved, now)
        stores += ViewModelStore().apply { put("form", model) }
        return model
    }
    private fun managedTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { stores.forEach(ViewModelStore::clear); stores.clear(); runCurrent() }
    }
    private fun snapshot(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun button(model: SourceLoginFormViewModel) = model.state.value.rows.first { it.type == RowUi.Type.button }
    private class Fake(v2: Boolean) : SourceLoginFormRepository {
        override val definition = SourceLoginDefinition("source", v2, emptyMap())
        var rows = listOf(SourceLoginRow("Name", RowUi.Type.text, "name", default = "default", modern = v2),
            SourceLoginRow("Password", RowUi.Type.password, "password", modern = v2),
            SourceLoginRow("Button", RowUi.Type.button, "button", action = "submit", countdown = 3, modern = v2),
            SourceLoginRow("Select", RowUi.Type.select, "select", options = listOf("A", "B"), modern = v2),
            SourceLoginRow("Toggle", RowUi.Type.toggle, "toggle", options = listOf("☐", "☑"), modern = v2))
        var readyGate: CompletableDeferred<Unit>? = null; var readyError: Exception? = null
        var renderGate: CompletableDeferred<Unit>? = null; var ignoreCancellation = false
        override suspend fun ready(): SourceLoginDefinition { readyGate?.await(); readyError?.let { throw it }; return definition }
        var stored = emptyMap<String, String>(); var renders = 0; var renderError: Exception? = null
        var command = LoginUiV2.ActionResult(); var saveResult = true; var loginError: Exception? = null
        val persisted = mutableListOf<Map<String, String>>(); val storedJson = mutableListOf<String>(); val actionForms = mutableListOf<Map<String, String>>()
        var clears = 0; var headerDeletes = 0; var headerValue: String? = null
        override suspend fun render(values: Map<String, String>, stateJson: String): SourceLoginRendered { renders++; val captured = rows; if (ignoreCancellation) withContext(NonCancellable) { renderGate?.await() } else renderGate?.await(); renderError?.let { throw it }; return SourceLoginRendered(captured, stored) }
        override suspend fun label(script: String, values: Map<String, String>) = "label"
        override suspend fun legacyAction(script: String, values: Map<String, String>, long: Boolean, java: Any) = Unit
        override suspend fun legacyLogin(values: Map<String, String>, java: Any): Boolean { loginError?.let { throw it }; return true }
        override suspend fun action(action: String, stateJson: String, values: Map<String, String>): LoginUiV2.ActionResult { actionForms += values; return command }
        override suspend fun store(json: String): Boolean { storedJson += json; return saveResult }
        override suspend fun persist(values: Map<String, String>) { persisted += values }
        override suspend fun header() = headerValue
        override suspend fun deleteHeader() { headerDeletes++ }
        override suspend fun clear() { clears++ }
    }
}
