package io.legado.app.ui.autoTask

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.theme.rememberLegadoColors
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class AutoTaskDebugUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<AutoTaskDebugViewModel>(); private val repos = mutableListOf<Fake>()
    private fun model(repo: Fake): AutoTaskDebugViewModel { lateinit var model: AutoTaskDebugViewModel
        compose.runOnIdle { model = AutoTaskDebugViewModel(repo, SavedStateHandle(), "id"); models += model; repos += repo }; return model }
    @After fun after() { compose.runOnIdle { models.forEach { it.stop(); it.viewModelScope.cancel() }; repos.forEach { it.leases.forEach { lease -> lease.result.complete("cleanup") } } } }
    @Test fun pausedUiKeepsExecutionLeaseAndResumeShowsLogThenRerunCancelsOnlyOldRun() {
        val repo = Fake(); val model = model(repo); val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { AutoTaskDebugRoute(model, {}, {}) } } }
        compose.waitUntil { repo.leases.size == 1 }; val lease = repo.leases.single()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; lease.log("while paused") }
        assertEquals(0, lease.closes); assertTrue(model.uiState.value.isRunning)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("while paused").assertExists()
        compose.onNodeWithTag("task-debug-run").performClick()
        compose.runOnIdle { assertEquals(1, lease.closes); assertEquals(1, repo.leases.size); lease.log("late"); lease.result.complete("late result") }
        compose.waitUntil { repo.leases.size == 2 }
        compose.runOnIdle { repo.leases.last().log("fresh") }
        compose.onNodeWithText("fresh").assertExists(); compose.onNodeWithText("late").assertDoesNotExist()
    }
    @Test fun backReleasesImmediatelyAndMissingCloseWaitsForResumeExactlyOnce() {
        val repo = Fake(); val model = model(repo); var backs = 0
        compose.setContent { LegadoComposeTheme { AutoTaskDebugRoute(model, { assertEquals(1, repo.leases.single().closes); backs++ }, {}) } }
        compose.waitUntil { repo.leases.size == 1 }
        compose.onNodeWithContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.back)).performClick()
        compose.waitUntil { backs == 1 }; compose.waitForIdle(); assertEquals(1, backs)
    }
    @Test fun missingTaskDoesNotCloseWhilePausedAndDoesNotRepeatOnSubsequentResume() {
        val repo = Fake(); repo.missing = true; val model = model(repo); val owner = Owner(); var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { LegadoComposeTheme { AutoTaskDebugRoute(model, { error("wrong close") }, { closes++ }) } } }
        compose.waitUntil { model.uiState.value.taskMissing }; assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }; compose.waitUntil { closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle(); assertEquals(1, closes); assertTrue(repo.leases.isEmpty())
    }
    @Test fun shortDarkScreenAutoScrollsNewestOutputAndKeepsExplicitRunButtonAccessible() {
        var runs = 0
        compose.setContent { LegadoComposeTheme(colors = rememberLegadoColors().copy(background = Color(0xff111111), bottomBackground = Color(0xff111111), textPrimary = Color.White, textSecondary = Color.LightGray, isLight = false)) {
            AutoTaskDebugScreen(AutoTaskDebugUiState(output = (0..600).joinToString("\n") { "line $it" }, isLoading = false, issue = AutoTaskDebugIssue.Interrupted), { runs++ }, {}, Modifier.height(320.dp))
        } }
        compose.waitUntil {
            val range = compose.onNodeWithTag("task-debug-output").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            range.maxValue() > 0 && range.value() == range.maxValue()
        }
        compose.onNodeWithTag("task-debug-issue").assertTextEquals(ApplicationProvider.getApplicationContext<Context>().getString(R.string.auto_task_debug_interrupted))
        compose.onNodeWithTag("task-debug-run").assertIsDisplayed().performClick(); assertEquals(1, runs)
    }
    @Test fun selectionContainerCopiesActualSelectedLogWord() {
        val toolbar = CaptureToolbar(); val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; val before = clipboard.primaryClip
        try {
            compose.setContent { CompositionLocalProvider(LocalTextToolbar provides toolbar) { LegadoComposeTheme {
                AutoTaskDebugScreen(AutoTaskDebugUiState(output = "copyable", isLoading = false), {}, {})
            } } }
            compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("", "baseline")) }
            compose.onNodeWithText("copyable").performTouchInput { longClick(Offset(40f, 35f)) }
            compose.waitUntil { toolbar.copy != null }; compose.runOnIdle { toolbar.copy!!.invoke() }
            compose.waitUntil { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == "copyable" }
        } finally { compose.runOnIdle { if (before != null) clipboard.setPrimaryClip(before) else clipboard.setPrimaryClip(ClipData.newPlainText("", "")) } }
    }
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle get() = registry }
    private class CaptureToolbar : TextToolbar {
        var copy: (() -> Unit)? = null; override var status = TextToolbarStatus.Hidden
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) { copy = onCopyRequested; status = TextToolbarStatus.Shown }
    }
    private class Fake : AutoTaskDebugRepository {
        var missing = false; var failLoad = false; var busy = false; var closedCount = 0
        var acquireGate: CompletableDeferred<Unit>? = null; val leases = mutableListOf<Lease>(); val records = mutableMapOf<String, AutoTaskDebugRecord>()
        override suspend fun load(id: String): AutoTaskDebugSnapshot? { if (failLoad) error("load failed"); return if (missing) null else AutoTaskDebugSnapshot(id, "source", "json") }
        override suspend fun acquire(task: AutoTaskDebugSnapshot, log: (String) -> Unit): AutoTaskDebugLease? {
            acquireGate?.await(); if (busy) return null
            return Lease(log) { closedCount++ }.also { leases += it }
        }
        override suspend fun read(session: String) = records[session]
        override suspend fun write(session: String, record: AutoTaskDebugRecord) { if (record.revision >= (records[session]?.revision ?: -1)) records[session] = record }
    }
    private class Lease(val log: (String) -> Unit, private val closed: () -> Unit) : AutoTaskDebugLease {
        val result = CompletableDeferred<String>(); var closes = 0; var runs = 0
        override suspend fun run(): AutoTaskDebugResult = withContext(NonCancellable) { runs++; AutoTaskDebugResult(result.await()) }
        override fun close() { if (closes == 0) { closes++; closed() } }
    }
}
