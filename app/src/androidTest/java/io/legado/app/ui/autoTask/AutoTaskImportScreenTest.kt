package io.legado.app.ui.autoTask

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class AutoTaskImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: AutoTaskImportViewModel
    private class Fake : AutoTaskImportRepository {
        var rows = (0..2).map { AutoTaskImportItem(it.toString(), "id$it", "Task $it", true, "*/30 * * * *", null,
            "script $it", AutoTaskImportStatus.entries[it]) }
        var gate: CompletableDeferred<Unit>? = null; var commitGate: CompletableDeferred<Unit>? = null
        var fail = false; var commits = 0; var edits = 0; var selected: Set<String>? = null
        override suspend fun load(id: String): AutoTaskImportSession { gate?.await(); if (fail) error("source failed"); return AutoTaskImportSession(rows) }
        override suspend fun edit(id: String, key: String, json: String): AutoTaskImportItem {
            edits++; val updated = rows.first { it.key == key }.copy(name = json, status = AutoTaskImportStatus.Exists)
            rows = rows.map { if (it.key == key) updated else it }; return updated
        }
        override suspend fun commit(id: String, selected: Set<String>) { commits++; this.selected = selected; commitGate?.await() }
    }
    private fun show(repo: Fake, editor: (String, String) -> Unit = { _, _ -> }, close: () -> Unit = {}) {
        compose.runOnIdle { model = AutoTaskImportViewModel(repo, SavedStateHandle(), "session") }
        compose.setContent { LegadoComposeTheme { AutoTaskImportRoute(model, { true }, editor, close, {}) } }
    }
    private fun loaded() { compose.waitUntil(5000) { !model.state.value.loading } }
    @After fun cleanup() { if (::model.isInitialized) compose.runOnIdle { model.stop() } }
    @Test fun accessibleRowsExposeDefaultSelectionAllCountsAndLocalizedReadableTitle() {
        show(Fake()); loaded()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("auto-task-import-title").assertTextEquals(context.getString(R.string.import_auto_task))
        compose.onNodeWithTag("auto-task-import-row-0").assertIsOn(); compose.onNodeWithTag("auto-task-import-row-1").assertIsOn(); compose.onNodeWithTag("auto-task-import-row-2").assertIsOff()
        compose.onNodeWithTag("auto-task-import-all").assertTextEquals(context.getString(R.string.select_all_count, 2, 3))
        compose.onNodeWithTag("auto-task-import-all").performClick(); compose.onNodeWithTag("auto-task-import-row-2").assertIsOn()
        compose.onNodeWithTag("auto-task-import-all").performClick(); compose.onNodeWithTag("auto-task-import-row-0").assertIsOff()
        compose.onNodeWithTag("auto-task-import-row-1").performClick(); compose.runOnIdle { assertEquals(setOf("1"), model.state.value.selected) }
    }
    @Test fun editClickDoesNotToggleRowAndMatchingCodeCallbackUpdatesNameAndRecompares() {
        val repo = Fake(); val requests = mutableListOf<Pair<String, String>>(); show(repo, editor = { code, request -> requests += code to request }); loaded()
        compose.onNodeWithTag("auto-task-import-edit-0").performClick()
        compose.waitUntil { requests.isNotEmpty() }
        compose.runOnIdle { assertEquals(setOf("0", "1"), model.state.value.selected); assertEquals("script 0", requests.single().first); model.codeSaved("Edited", requests.single().second) }
        compose.waitUntil { !model.state.value.busy }
        compose.onNodeWithText("Edited").assertExists(); compose.onNodeWithTag("auto-task-import-row-0").assertIsOff()
        compose.runOnIdle { model.codeSaved("Edited", requests.single().second); assertEquals(1, repo.edits) }
    }
    @Test fun activeImportDisablesRowAndButtonsAndDeliversCloseAfterCompletion() {
        val repo = Fake().apply { commitGate = CompletableDeferred() }; var closes = 0; show(repo, close = { closes++ }); loaded()
        compose.onNodeWithTag("auto-task-import-confirm").performClick()
        compose.onNodeWithTag("auto-task-import-working").assertExists(); compose.onNodeWithTag("auto-task-import-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("auto-task-import-confirm").assertIsNotEnabled(); compose.onNodeWithTag("auto-task-import-row-0").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, repo.commits); assertEquals(setOf("0", "1"), repo.selected); assertEquals(0, closes); repo.commitGate!!.complete(Unit) }
        compose.waitUntil { closes == 1 }
    }
    @Test fun pendingFailureRetryAndCancelNeverImport() {
        val repo = Fake().apply { gate = CompletableDeferred(); fail = true }; var closes = 0; show(repo, close = { closes++ })
        compose.onNodeWithTag("auto-task-import-confirm").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }; loaded(); compose.onNodeWithTag("auto-task-import-error").assertTextEquals("ImportError:source failed")
        compose.runOnIdle { repo.fail = false }; compose.onNodeWithTag("auto-task-import-retry").performClick(); loaded()
        compose.onNodeWithTag("auto-task-import-cancel").performClick(); compose.waitUntil { closes == 1 }
        compose.runOnIdle { assertEquals(0, repo.commits) }
    }
}
