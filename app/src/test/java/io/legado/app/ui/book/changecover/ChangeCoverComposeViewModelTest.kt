package io.legado.app.ui.book.changecover

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeCoverComposeViewModelTest {
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
    fun authorIsNormalizedBeforeCacheAndAutomaticSearchOnlyFollowsRepositoryDecision() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = ChangeCoverComposeViewModel(repo, SavedStateHandle(), "Name", "作者：Author 著")
            val store = owned(model)
            try {
                runCurrent()
                assertEquals(ChangeCoverTarget("Name", "Author"), repo.target)
                assertTrue(repo.calls.isEmpty())
                model.startStop()
                runCurrent()
                assertEquals(listOf(false), repo.calls)
            } finally {
                store.clear()
            }
        }

    @Test
    fun automaticSearchIsOwnedAndStopCancelsAllWorkThenNextButtonRefreshes() =
        runTest(dispatcher) {
            val repo = Fake().apply { auto = true }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                assertEquals(1, repo.active)
                assertEquals(ChangeCoverStatus.Running, model.state.value.snapshot!!.status)
                model.startStop()
                runCurrent()
                assertEquals(0, repo.active)
                assertEquals(ChangeCoverStatus.Idle, model.state.value.snapshot!!.status)
                model.startStop()
                runCurrent()
                assertEquals(listOf(false, false), repo.calls)
                store.clear()
                runCurrent()
                assertEquals(0, repo.active)
            } finally {
                store.clear()
            }
        }

    @Test
    fun ruleReadyButtonResumesInsteadOfClearingAndReevaluatingRule() =
        runTest(dispatcher) {
            val repo = Fake().apply { initialStatus = ChangeCoverStatus.RuleReady }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.startStop()
                runCurrent()
                assertEquals(listOf(true), repo.calls)
            } finally {
                store.clear()
            }
        }

    @Test
    fun diskSessionRestoreResumesOnlyRunningSnapshotAndUsesIncreasingRevisions() =
        runTest(dispatcher) {
            val saved = SavedStateHandle(mapOf("session" to "stable"))
            val repo =
                Fake().apply {
                    initialStatus = ChangeCoverStatus.Running
                    auto = true
                    initialRevision = 20
                }
            val model = newModel(repo, saved)
            val store = owned(model)
            try {
                runCurrent()
                assertEquals("stable", repo.session)
                assertEquals(listOf(true), repo.calls)
                assertEquals(21L, model.state.value.snapshot!!.revision)
                model.stop()
                runCurrent()
                assertEquals(22L, repo.saved!!.revision)
                assertFalse(saved.keys().any { it.contains("covers") || it.contains("bitmap") })
            } finally {
                store.clear()
            }
        }

    @Test
    fun initialFailureBlocksSearchAndRetryReadsOriginalDataRatherThanWritingBlankSnapshot() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadFails = true }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                assertEquals("load failed", model.state.value.error)
                model.startStop()
                assertTrue(repo.calls.isEmpty())
                assertNull(repo.saved)
                repo.loadFails = false
                model.retry()
                runCurrent()
                assertNull(model.state.value.error)
                assertNotNull(model.state.value.snapshot)
            } finally {
                store.clear()
            }
        }

    @Test
    fun selectingDefaultCancelsSearchAndCallbackResultRestoresButConsumedResultDoesNotReplay() =
        runTest(dispatcher) {
            val repo = Fake().apply { auto = true }
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            runCurrent()
            model.select("default")
            model.select("default")
            runCurrent()
            assertEquals(0, repo.active)
            val restoredSaved = copy(saved)
            val restored = newModel(repo, restoredSaved)
            val other = owned(restored)
            store.clear()
            try {
                runCurrent()
                assertEquals("use_default_cover", restored.state.value.selected)
                assertTrue(restored.state.value.finished)
                restored.consume("use_default_cover")
                restored.startStop()
                restored.retry()
                assertNull(restored.state.value.selected)
                val next = newModel(repo, copy(restoredSaved))
                val nextStore = owned(next)
                try {
                    assertNull(next.state.value.selected)
                    assertTrue(next.state.value.finished)
                } finally {
                    nextStore.clear()
                }
            } finally {
                other.clear()
            }
        }

    @Test
    fun saveFailureShowsRetryAndDoesNotLoseLatestGridOrSavedSelectionContract() =
        runTest(dispatcher) {
            val repo = Fake().apply { saveFails = true }
            val model = newModel(repo)
            val store = owned(model)
            try {
                runCurrent()
                model.startStop()
                runCurrent()
                assertEquals("save failed", model.state.value.error)
                assertNotNull(model.state.value.snapshot)
                repo.saveFails = false
                model.retry()
                runCurrent()
                assertNull(model.state.value.error)
                assertNotNull(repo.saved)
            } finally {
                store.clear()
            }
        }

    @Test
    fun hugeCoverSelectionPersistsBeforeDeliveryAndSavedStateContainsOnlyStableIdentity() =
        runTest(dispatcher) {
            val huge = "data:image/png;base64," + "X".repeat(1200000)
            val repo =
                Fake().apply {
                    extra = ChangeCoverItem("book:large", "source", "Source", huge)
                    saveGate = CompletableDeferred()
                }
            val saved = SavedStateHandle()
            val model = newModel(repo, saved)
            val store = owned(model)
            try {
                runCurrent()
                model.select("book:large")
                runCurrent()
                assertNull(model.state.value.selected)
                assertNull(saved.get<String>("selectedId"))
                repo.saveGate!!.complete(Unit)
                runCurrent()
                assertEquals(huge, model.state.value.selected)
                assertEquals("book:large", saved.get<String>("selectedId"))
                assertTrue(saved.keys().none { saved.get<Any?>(it) == huge })
                val restoredSaved = copy(saved)
                val restored = newModel(repo, restoredSaved)
                val other = owned(restored)
                try {
                    assertNull(restored.state.value.selected)
                    runCurrent()
                    assertEquals(huge, restored.state.value.selected)
                    restored.consume(huge)
                    val next = newModel(repo, copy(restoredSaved))
                    val last = owned(next)
                    try {
                        runCurrent()
                        assertNull(next.state.value.selected)
                    } finally {
                        last.clear()
                    }
                } finally {
                    other.clear()
                }
            } finally {
                store.clear()
            }
        }

    private fun newModel(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        ChangeCoverComposeViewModel(repo, saved, "Name", "Author")

    private fun owned(model: ChangeCoverComposeViewModel) =
        ViewModelStore().apply { put("cover", model) }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private class Fake : ChangeCoverRepository {
        var target: ChangeCoverTarget? = null
        var session = ""
        var auto = false
        var initialStatus = ChangeCoverStatus.Idle
        var initialRevision = 0L
        var active = 0
        var loadFails = false
        var saveFails = false
        var saved: ChangeCoverSnapshot? = null
        var extra: ChangeCoverItem? = null
        var saveGate: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<Boolean>()

        override suspend fun initial(
            session: String,
            target: ChangeCoverTarget,
        ): ChangeCoverInitial {
            this.session = session
            this.target = target
            if (loadFails) error("load failed")
            return ChangeCoverInitial(
                ChangeCoverSnapshot(
                    target,
                    listOfNotNull(
                        ChangeCoverItem("default", "", "默认封面", "use_default_cover"),
                        extra,
                    ),
                    listOf("source"),
                    initialStatus,
                    initialRevision,
                ),
                auto,
            )
        }

        override fun search(
            snapshot: ChangeCoverSnapshot,
            resume: Boolean,
        ): Flow<ChangeCoverSnapshot> = flow {
            calls += resume
            active++
            try {
                emit(snapshot.copy(status = ChangeCoverStatus.Running))
                awaitCancellation()
            } finally {
                active--
            }
        }

        override suspend fun selected(session: String, id: String) =
            saved!!.covers.first { it.id == id }.coverUrl

        override suspend fun save(session: String, snapshot: ChangeCoverSnapshot) {
            saveGate?.await()
            if (saveFails) error("save failed")
            saved = snapshot
        }
    }
}
