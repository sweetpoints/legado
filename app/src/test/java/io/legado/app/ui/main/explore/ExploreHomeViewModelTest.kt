package io.legado.app.ui.main.explore

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.login.SourceLoginJsExtensions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<ExploreHomeViewModel>()

    @Before
    fun prepare() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun close() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(repository: Repository, storage: Storage = Storage()): ExploreHomeViewModel =
        ExploreHomeViewModel(repository, storage, dispatcher).also {
            models += it
            it.viewModelScope.launch { it.observeResumed() }
        }

    @Test
    fun acceptedCheckpointDoesNotDiscardAnEditMadeDuringTheWrite() =
        runTest(dispatcher) {
            val storage = Storage()
            val manager = model(Repository(), storage)
            advanceUntilIdle()
            manager.expand("a")
            advanceUntilIdle()
            manager.value(2, "before")
            advanceUntilIdle()
            storage.onWrite = {
                storage.onWrite = null
                manager.value(2, "latest")
            }
            manager.query("")
            advanceUntilIdle()
            assertEquals("latest", manager.state.value.controls.first { it.id == 2 }.value)
            assertEquals("latest", storage.snapshot.values["a"]?.get("text"))
        }

    @Test
    fun missingGroupFallsBackAndSourceRemovalClearsExpandedControls() =
        runTest(dispatcher) {
            val repository = Repository()
            val manager = model(repository)
            runCurrent()
            manager.query("group:novel")
            runCurrent()
            assertEquals("group:novel", manager.state.value.query)
            repository.groupRows.value = emptyList()
            runCurrent()
            assertEquals("", manager.state.value.query)
            manager.expand("a")
            runCurrent()
            assertTrue(manager.state.value.controls.isNotEmpty())
            repository.rows.value = repository.rows.value.filterNot { it.url == "a" }
            runCurrent()
            assertNull(manager.state.value.expandedUrl)
            assertTrue(manager.state.value.controls.isEmpty())
        }

    @Test
    fun latePanelCannotReplaceNewOwnerOrPausedOwner() =
        runTest(dispatcher) {
            val repository = Repository()
            val manager = model(repository)
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            repository.panelGate = gate
            manager.expand("a")
            runCurrent()
            manager.expand("b")
            runCurrent()
            manager.pause()
            gate.complete(Unit)
            runCurrent()
            assertEquals("b", manager.state.value.expandedUrl)
            assertTrue(manager.state.value.controls.isEmpty())
            repository.panelGate = null
            manager.observeResumed()
            runCurrent()
            assertEquals("b label", manager.state.value.controls.first().label)
        }

    @Test
    fun fiveControlKindsKeepOriginalNavigationAndFrozenScriptValues() =
        runTest(dispatcher) {
            val manager = model(Repository())
            runCurrent()
            manager.expand("a")
            runCurrent()
            manager.control(0)
            runCurrent()
            val open = manager.state.value.effect!!
            assertEquals("open", open.action)
            assertEquals("original title", open.title)
            assertEquals("https://category", open.value)
            manager.deliver(open, { true }, {})
            manager.value(2, "full draft")
            manager.control(2)
            runCurrent()
            val script = manager.state.value.effect!!
            manager.value(2, "later edit")
            assertEquals("full draft", script.values["text"])
            assertEquals("action-2", script.value)
            manager.deliver(script, { true }, {})
            manager.control(3)
            runCurrent()
            assertEquals("off", manager.state.value.controls.find { it.id == 3 }!!.value)
            assertEquals("off", manager.state.value.effect!!.values["toggle"])
        }

    @Test
    fun largeDraftAndEffectRestoreFromPrivateSessionWhileBundleHasOnlyToken() =
        runTest(dispatcher) {
            val repository = Repository()
            val storage = Storage()
            val manager = model(repository, storage)
            runCurrent()
            manager.expand("a")
            runCurrent()
            val draft = "large draft".repeat(20_000)
            manager.value(2, draft)
            manager.control(2)
            runCurrent()
            val restored = model(repository, storage)
            runCurrent()
            assertEquals(draft, restored.state.value.effect!!.values["text"])
            val saved = SavedStateHandle()
            val token = ExploreHomeViewModel.token(saved)
            assertEquals(token, ExploreHomeViewModel.token(saved))
            assertEquals(setOf("exploreHome.session"), saved.keys())
        }

    @Test
    fun durableAcceptanceRechecksOwnerAndReceiptsPreventDuplicateLaunches() =
        runTest(dispatcher) {
            val storage = Storage()
            val manager = model(Repository(), storage)
            runCurrent()
            manager.effect("search", "a")
            runCurrent()
            val request = manager.state.value.effect!!
            var resumed = true
            var launches = 0
            storage.onWrite = { if (request.id in it.receipts) resumed = false }
            assertFalse(manager.deliver(request, { resumed }, { launches++ }))
            assertEquals(0, launches)
            assertNotNull(manager.state.value.effect)
            storage.onWrite = null
            resumed = true
            assertTrue(manager.deliver(request, { resumed }, { launches++ }))
            assertFalse(manager.deliver(request, { resumed }, { launches++ }))
            assertEquals(1, launches)
        }

    @Test
    fun interruptedOperationRestoresWarningAndNeverRepeatsBusinessMutation() =
        runTest(dispatcher) {
            val storage = Storage().apply { snapshot = ExploreHomeSession(pendingOperation = true) }
            val repository = Repository()
            val manager = model(repository, storage)
            runCurrent()
            assertNotNull(manager.state.value.error)
            assertTrue(repository.deleted.isEmpty())
        }

    @Test
    fun expandedMetadataRefreshKeepsControlsUntilReplacementAndEmptyResultClearsThem() =
        runTest(dispatcher) {
            val repository = Repository()
            val manager = model(repository)
            runCurrent()
            manager.expand("a")
            runCurrent()
            val scrollRequest = manager.state.value.scrollRequest
            val gate = CompletableDeferred<Unit>()
            repository.panelGate = gate
            repository.label = "updated label"
            repository.rows.value =
                repository.rows.value.map {
                    if (it.url == "a") it.copy(revision = "updated") else it
                }
            runCurrent()
            assertTrue(manager.state.value.panelLoading)
            assertEquals("a label", manager.state.value.controls.first().label)
            gate.complete(Unit)
            runCurrent()
            assertEquals("updated label", manager.state.value.controls.first().label)
            assertEquals(scrollRequest, manager.state.value.scrollRequest)
            repository.panelGate = null
            repository.emptyPanel = true
            manager.refresh()
            runCurrent()
            assertTrue(manager.state.value.controls.isEmpty())
            assertEquals("a", manager.state.value.expandedUrl)
        }

    @Test
    fun rejectedDurableReceiptKeepsEffectAndNeverLaunchesNativeWork() =
        runTest(dispatcher) {
            val storage = Storage()
            val manager = model(Repository(), storage)
            runCurrent()
            manager.effect("search", "a")
            runCurrent()
            val effect = manager.state.value.effect!!
            storage.rejectWrite = true
            var launches = 0
            val failure = runCatching { manager.deliver(effect, { true }, { launches++ }) }
            assertTrue(failure.isFailure)
            assertEquals(0, launches)
            assertEquals(effect, manager.state.value.effect)
            assertFalse(effect.id in storage.snapshot.receipts)
        }

    @Test
    fun failedPrivateReadBlocksEditsAndBusinessWritesUntilRetryRestoresOriginalPayload() =
        runTest(dispatcher) {
            val original =
                ExploreHomeSession(
                    revision = 7,
                    query = "original query",
                    effect =
                        ExploreHomeEffect(
                            "original effect",
                            "open",
                            "a",
                            "title",
                            "full original URL",
                        ),
                )
            val storage =
                Storage().apply {
                    snapshot = original
                    failRead = true
                }
            val repository = Repository()
            val manager = model(repository, storage)
            runCurrent()
            assertFalse(manager.state.value.sessionLoaded)
            assertNotNull(manager.state.value.error)
            manager.query("must not overwrite")
            manager.effect("manage")
            manager.top("a")
            runCurrent()
            assertEquals(0, storage.writes)
            assertEquals(original, storage.snapshot)
            assertTrue(repository.topCalls.isEmpty())
            storage.failRead = false
            manager.retry()
            runCurrent()
            assertTrue(manager.state.value.sessionLoaded)
            assertEquals("original query", manager.state.value.query)
            assertEquals(original.effect, manager.state.value.effect)
            assertEquals(2, storage.reads)
        }

    private class Storage : ExploreHomeSessionStorage {
        var snapshot = ExploreHomeSession()
        var onWrite: ((ExploreHomeSession) -> Unit)? = null
        var rejectWrite = false
        var failRead = false
        var reads = 0
        var writes = 0

        override fun read(): ExploreHomeSession {
            reads++
            if (failRead) error("private read failed")
            return snapshot
        }

        override fun write(snapshot: ExploreHomeSession): Boolean {
            if (rejectWrite) return false
            writes++
            this.snapshot = snapshot
            onWrite?.invoke(snapshot)
            return true
        }

        override fun delete() = Unit
    }

    private class Repository : ExploreHomeRepository {
        val rows =
            MutableStateFlow(
                listOf(ExploreHomeSource("a", "Alpha", false), ExploreHomeSource("b", "Beta", true))
            )
        val groupRows = MutableStateFlow(listOf("novel"))
        var panelGate: CompletableDeferred<Unit>? = null
        var label: String? = null
        var emptyPanel = false
        val deleted = mutableListOf<String>()
        val topCalls = mutableListOf<String>()

        override fun sources(query: String) = rows.map {
            if (query.isBlank() || query.startsWith("group:")) it
            else it.filter { row -> row.name.contains(query) }
        }

        override fun groups() = groupRows

        override suspend fun panel(url: String, refresh: Boolean): ExploreHomePanel {
            withContext(NonCancellable) { panelGate?.await() }
            if (emptyPanel) return ExploreHomePanel(emptyList())
            return ExploreHomePanel(
                listOf(
                    control(0, "url", "original title", label ?: "$url label", "https://category"),
                    control(1, "button", "button"),
                    control(2, "text", "text"),
                    control(3, "toggle", "toggle", value = "on"),
                    control(4, "select", "select", value = "on"),
                )
            )
        }

        override suspend fun execute(
            url: String,
            action: String,
            values: Map<String, String>,
            activity: AppCompatActivity?,
            callback: SourceLoginJsExtensions.Callback,
        ) = Unit

        override suspend fun saveValues(url: String, values: Map<String, String>) = Unit

        override suspend fun savePendingValues() = Unit

        override suspend fun top(url: String) {
            topCalls += url
        }

        override suspend fun delete(url: String) {
            deleted += url
        }

        override suspend fun searchSource(url: String) = BookSourcePart(bookSourceUrl = url)

        override suspend fun eInkMode() = false

        override suspend fun showFastScroller() = true

        private fun control(
            id: Int,
            type: String,
            title: String,
            label: String = title,
            url: String? = null,
            value: String = "",
        ) =
            ExploreHomeControl(
                id,
                type,
                title,
                label,
                url,
                listOf("on", "off"),
                value,
                ExploreControlStyle(),
                action = "action-$id",
            )
    }
}
