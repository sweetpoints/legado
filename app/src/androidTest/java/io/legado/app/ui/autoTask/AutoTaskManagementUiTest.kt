package io.legado.app.ui.autoTask

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.theme.rememberLegadoColors
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AutoTaskManagementUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun label(id: Int) = context.getString(id)
    private fun attach(model: AutoTaskManagementViewModel, deliver: (AutoTaskManagementEffect, String?) -> Unit = { _, _ -> }) {
        compose.setContent { LegadoComposeTheme { Box(Modifier.height(620.dp)) { AutoTaskManagementRoute(model, deliver, { throw AssertionError(it) }) } } }
        compose.waitUntil { !model.state.value.loading }
    }
    @Test fun searchPreservesHiddenSelectionAndBatchEnabledTargetsOnlyVisibleRows() {
        val repo = Fake(); lateinit var model: AutoTaskManagementViewModel
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            attach(model)
            compose.onNodeWithTag("task-select-a").performClick()
            compose.onNodeWithTag("task-select-b").performClick()
            compose.onNodeWithTag("tasks-search").performTextReplacement(" beta ")
            compose.onNodeWithTag("tasks-count").assertTextEquals("1/1")
            compose.onNodeWithTag("task-a").assertDoesNotExist()
            compose.onNodeWithTag("tasks-batch").performClick()
            compose.onNodeWithText(label(R.string.disable_selection)).performClick()
            compose.waitUntil { repo.enabledCalls.isNotEmpty() }
            assertEquals(listOf("b") to false, repo.enabledCalls.single())
            compose.onNodeWithTag("tasks-search").performTextReplacement("")
            compose.onNodeWithTag("task-select-a").assertIsOn()
            compose.onNodeWithTag("task-select-b").assertIsOn()
            compose.onNodeWithTag("task-debug-a").performClick()
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun rowPopupPreservesLoginFirstOrderAndNativeActionsWithCapabilityGate() {
        val repo = Fake(); lateinit var model: AutoTaskManagementViewModel
        val effects = mutableListOf<AutoTaskManagementEffect>()
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            attach(model, { effect, _ -> effects += effect })
            compose.onNodeWithTag("task-menu-a").performClick()
            val names = listOf(R.string.login, R.string.auto_task_log, R.string.auto_task_move_up, R.string.auto_task_move_down, R.string.delete)
            val bounds = names.map { compose.onNodeWithText(label(it)).fetchSemanticsNode().boundsInRoot }
            assertTrue(bounds.zipWithNext().all { (first, second) -> first.top < second.top })
            compose.onNodeWithText(label(R.string.login)).performClick()
            compose.waitUntil { effects.isNotEmpty() }
            assertEquals(AutoTaskManagementAction.Login, effects.single().action); assertEquals("a", effects.single().value)
            compose.onNodeWithTag("task-menu-b").performClick()
            compose.onNodeWithText(label(R.string.login)).assertDoesNotExist()
            compose.onNodeWithText(label(R.string.auto_task_move_up)).performClick()
            compose.waitUntil { repo.orders.isNotEmpty() }; assertEquals(listOf("b", "a", "c"), repo.orders.single())
            compose.onNodeWithTag("task-edit-a").performClick()
            compose.waitUntil { effects.size == 2 }; assertEquals(AutoTaskManagementAction.Edit, effects.last().action)
            compose.onNodeWithTag("task-debug-a").performClick()
            compose.waitUntil { effects.size == 3 }; assertEquals(AutoTaskManagementAction.Debug, effects.last().action)
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun cronValidationKeepsInputAndDeleteRequiresConfirmationWhileLogClearUsesExactTask() {
        val repo = Fake(); lateinit var model: AutoTaskManagementViewModel
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            attach(model); compose.onNodeWithTag("task-select-a").performClick()
            compose.onNodeWithTag("tasks-batch").performClick(); compose.onNodeWithText(label(R.string.auto_task_batch_cron)).performClick()
            compose.onNodeWithTag("tasks-cron").performTextReplacement("bad")
            compose.onNodeWithTag("tasks-cron-save").performClick()
            compose.onNodeWithText(label(R.string.auto_task_cron_invalid)).assertExists(); assertTrue(repo.crons.isEmpty())
            compose.onNodeWithTag("tasks-cron").performTextReplacement(" 0 * * * * ")
            compose.onNodeWithTag("tasks-cron-save").performClick()
            compose.waitUntil { repo.crons.isNotEmpty() }; assertEquals(listOf("a") to "0 * * * *", repo.crons.single())
            compose.onNodeWithTag("task-menu-a").performClick(); compose.onNodeWithText(label(R.string.auto_task_log)).performClick()
            compose.onNodeWithTag("tasks-log").assertTextEquals("last log")
            compose.onNodeWithTag("tasks-log-clear").performClick(); compose.waitUntil { repo.logs == listOf("a") }
            compose.onNodeWithTag("task-menu-a").performClick(); compose.onAllNodesWithText(label(R.string.delete)).onLast().performClick()
            assertTrue(repo.deletes.isEmpty()); compose.onNodeWithTag("tasks-delete-confirm").performClick()
            compose.waitUntil { repo.deletes.isNotEmpty() }; assertEquals(listOf("a"), repo.deletes.single())
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun onlineHistoryRemovalAndPasteImportDeliverDiskPayloadAndExportCopyKeepsPassphraseModal() {
        val repo = Fake(); lateinit var model: AutoTaskManagementViewModel
        val delivered = mutableListOf<Pair<AutoTaskManagementEffect, String?>>()
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            attach(model, { effect, payload -> delivered += effect to payload })
            compose.onNodeWithTag("tasks-menu").performClick(); compose.onNodeWithText(label(R.string.import_on_line)).performClick()
            compose.waitUntil { !model.state.value.onlineLoading }
            compose.onNodeWithTag("tasks-history-delete-https://old.invalid/tasks.json").performClick()
            compose.waitUntil { repo.removed.isNotEmpty() }
            compose.onNodeWithTag("tasks-online-input").performTextReplacement(" [\"task\"] ")
            compose.onNodeWithTag("tasks-online-confirm").performClick()
            compose.waitUntil { delivered.isNotEmpty() }; assertEquals("[\"task\"]", delivered.single().second)
            compose.onNodeWithTag("tasks-menu").performClick(); compose.onNodeWithText(label(R.string.export)).performClick()
            compose.waitUntil { delivered.size == 2 }; assertEquals("all JSON", delivered.last().second)
            compose.waitUntil { repo.releases == 1 }
            compose.runOnIdle { model.exportReturned("https://export.invalid/file") }
            compose.onNodeWithTag("tasks-copy-passphrase").performClick()
            compose.waitUntil { delivered.size == 3 }; assertEquals("autoTask:phrase", delivered.last().first.value)
            compose.onNodeWithTag("tasks-export-url").assertExists()
            compose.onNodeWithTag("tasks-copy-export").performClick()
            compose.waitUntil { delivered.size == 4 }; assertEquals("https://export.invalid/file", delivered.last().first.value)
            compose.onNodeWithTag("tasks-export-url").assertDoesNotExist()
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun effectsWaitForResumeAndConsumedSnapshotDoesNotDeliverAgain() {
        val owner = Owner(); val saved = SavedStateHandle(); val repo = Fake()
        lateinit var model: AutoTaskManagementViewModel; var active by mutableStateOf<AutoTaskManagementViewModel?>(null); var deliveries = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; model = AutoTaskManagementViewModel(repo, saved); active = model }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { active?.let { current -> LegadoComposeTheme {
                AutoTaskManagementRoute(current, { effect, _ -> assertTrue(current.state.value.effects.none { it.id == effect.id }); deliveries++ }, { throw AssertionError(it) })
            } } } }
            compose.waitUntil { !model.state.value.loading }; compose.runOnIdle { model.action(AutoTaskManagementAction.Edit, "a") }
            assertEquals(0, deliveries)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { deliveries == 1 }
            compose.runOnIdle { active = null; model.viewModelScope.cancel(); model = AutoTaskManagementViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); active = model }
            compose.waitUntil { !model.state.value.loading }; compose.waitForIdle(); assertEquals(1, deliveries)
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun realSlideTouchCancelRestoresBaselineWithoutBatchWrites() {
        val repo = Fake(); lateinit var model: AutoTaskManagementViewModel
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            attach(model)
            compose.onNodeWithTag("task-select-c").performScrollTo().performClick()
            compose.onNodeWithTag("tasks-list").performScrollToIndex(0)
            val first = compose.onNodeWithTag("task-a").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithTag("task-b").fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag("tasks-list").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("tasks-list").performTouchInput {
                down(Offset(25f, first.center.y - list.top)); moveTo(Offset(25f, second.center.y - list.top), 400); advanceEventTime(100)
            }
            compose.waitUntil { "a" in model.state.value.selected && "b" in model.state.value.selected }
            compose.onNodeWithTag("tasks-list").performTouchInput { cancel() }
            compose.waitUntil { model.state.value.selected == setOf("c") }
            assertTrue(repo.enabledCalls.isEmpty()); assertTrue(repo.orders.isEmpty()); assertTrue(repo.deletes.isEmpty())
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun shortDarkScreenKeepsLastRowAccessibleAndCronDraftSurvivesVmRestoration() {
        val repo = Fake(); repo.rows.value = (0..20).map { AutoTaskListItem("$it", "Task $it", true, null, "status", false, null) }
        val saved = SavedStateHandle(); lateinit var model: AutoTaskManagementViewModel
        var active by mutableStateOf<AutoTaskManagementViewModel?>(null)
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, saved); active = model }
        try {
            compose.setContent { LegadoComposeTheme(colors = rememberLegadoColors().copy(background = Color(0xff111111), bottomBackground = Color(0xff111111), textPrimary = Color.White, textSecondary = Color.LightGray, isLight = false)) { Box(Modifier.height(320.dp)) { active?.let {
                AutoTaskManagementRoute(it, { _, _ -> }, { throw AssertionError(it) })
            } } } }
            compose.waitUntil { !model.state.value.loading }
            compose.onNodeWithTag("tasks-list").performScrollToIndex(20)
            compose.onNodeWithTag("task-select-20").performClick()
            compose.onNodeWithTag("tasks-batch").performClick(); compose.onNodeWithText(label(R.string.auto_task_batch_cron)).performClick()
            compose.onNodeWithTag("tasks-cron").performTextReplacement("*/30 * * * *")
            compose.runOnIdle { active = null; model.viewModelScope.cancel(); model = AutoTaskManagementViewModel(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); active = model }
            compose.waitUntil { !model.state.value.loading }
            compose.onNodeWithTag("tasks-cron").assertTextEquals("*/30 * * * *")
            compose.onNodeWithTag("tasks-cron-save").performClick(); compose.waitUntil { repo.crons.isNotEmpty() }
            assertEquals(listOf("20") to "*/30 * * * *", repo.crons.single())
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun damagedExportIsConsumedOnceDoesNotBlockEditAndFreshExportCanRetry() {
        val repo = Fake(); repo.failExport = true
        lateinit var model: AutoTaskManagementViewModel; var failures = 0
        val effects = mutableListOf<AutoTaskManagementEffect>()
        compose.runOnIdle { model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            compose.setContent { LegadoComposeTheme { AutoTaskManagementRoute(model, { effect, _ -> effects += effect }, { failures++ }) } }
            compose.waitUntil { !model.state.value.loading }; compose.runOnIdle { model.export(false) }
            compose.waitUntil { failures == 1 && repo.releases == 1 }
            compose.runOnIdle { model.action(AutoTaskManagementAction.Edit, "a") }
            compose.waitUntil { effects.size == 1 }; assertEquals(AutoTaskManagementAction.Edit, effects.single().action)
            assertTrue(model.state.value.effects.isEmpty()); assertEquals(1, failures)
            compose.runOnIdle { repo.failExport = false; model.export(false) }
            compose.waitUntil { effects.size == 2 && repo.releases == 2 }
            assertEquals(AutoTaskManagementAction.Export, effects.last().action); assertEquals(1, failures)
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun canceledNonCooperativePayloadCannotDeliverFromOldResumedCollectorAndFailedLauncherReleasesTicket() {
        val owner = Owner(); val repo = Fake(); val gate = CompletableDeferred<Unit>(); repo.exportGate = gate
        lateinit var model: AutoTaskManagementViewModel; var deliveries = 0; var failures = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; model = AutoTaskManagementViewModel(repo, SavedStateHandle()) }
        try {
            compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme {
                AutoTaskManagementRoute(model, { _, _ -> deliveries++; error("launcher unavailable") }, { failures++ })
            } } }
            compose.waitUntil { !model.state.value.loading }; compose.runOnIdle { model.export(false) }
            compose.waitUntil { repo.reads == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED; gate.complete(Unit) }
            compose.waitUntil { deliveries == 1 && repo.releases == 1 }
            assertEquals(2, repo.reads); assertEquals(1, failures); assertTrue(model.state.value.effects.isEmpty())
        } finally { gate.complete(Unit); compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class Fake : AutoTaskManagementRepository {
        val rows = MutableStateFlow(listOf(AutoTaskListItem("a", "Alpha", true, "* * * * *", "* * * * *", true, "last log"),
            AutoTaskListItem("b", "Beta", false, null, "", false, null), AutoTaskListItem("c", "Gamma", true, null, "", false, null)))
        val enabledCalls = mutableListOf<Pair<List<String>, Boolean>>(); val orders = mutableListOf<List<String>>()
        val crons = mutableListOf<Pair<List<String>, String>>(); val logs = mutableListOf<String>(); val deletes = mutableListOf<List<String>>()
        val removed = mutableListOf<String>(); val drafts = mutableMapOf<String, AutoTaskOnlineDraft>(); var releases = 0; var reads = 0; var failExport = false; var exportGate: CompletableDeferred<Unit>? = null
        override fun observe() = rows
        override suspend fun enabled(ids: List<String>, value: Boolean) { enabledCalls += ids to value }
        override suspend fun cron(ids: List<String>, value: String) { crons += ids to value }
        override suspend fun delete(ids: List<String>) { deletes += ids }
        override suspend fun reorder(ids: List<String>) { orders += ids }
        override suspend fun clearLog(id: String) { logs += id }
        override suspend fun history() = listOf("https://old.invalid/tasks.json")
        override suspend fun remember(url: String) = Unit
        override suspend fun removeHistory(url: String) { removed += url }
        override suspend fun export(ids: List<String>?) = AutoTaskExportTicket("ticket", "exportAutoTask.json")
        override suspend fun exportText(ticket: AutoTaskExportTicket): String { reads++; withContext(NonCancellable) { exportGate?.await() }; if (failExport) error("damaged receipt"); return "all JSON" }
        override suspend fun releaseExport(ticket: AutoTaskExportTicket) { releases++ }
        override suspend fun exportNotice(url: String) = AutoTaskExportNotice(url, "summary", "autoTask:phrase")
        override suspend fun readDraft(session: String) = drafts[session]
        override suspend fun writeDraft(session: String, draft: AutoTaskOnlineDraft) { if (draft.revision >= (drafts[session]?.revision ?: -1)) drafts[session] = draft }
    }
}
