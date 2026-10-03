package io.legado.app.ui.association

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val labels =
        BookImportSearchLabels(
            "enabled",
            "disabled",
            "login",
            "no group",
            "enabled explore",
            "disabled explore",
        )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    private val models = mutableListOf<BookImportViewModel>()

    @After
    fun teardown() {
        models.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        requests: Requests = Requests(),
    ) = BookImportViewModel(repo, requests, saved, "source", labels).also { models += it }

    @Test
    fun defaultsSpecialFiltersAndVisibleSelectionPreserveHiddenChoices() =
        runTest(dispatcher) {
            val model = model(Fake())
            runCurrent()
            assertEquals(setOf("a"), model.state.value.selected)
            model.search("disabled")
            assertEquals(
                listOf("b"),
                visibleBookImportItems(model.state.value, labels).map { it.key },
            )
            model.selectVisible()
            assertEquals(setOf("a", "b"), model.state.value.selected)
            model.selectVisible()
            assertEquals(setOf("a"), model.state.value.selected)
            model.search("login")
            assertEquals(
                listOf("b"),
                visibleBookImportItems(model.state.value, labels).map { it.key },
            )
            model.search("no group")
            assertEquals(
                listOf("a"),
                visibleBookImportItems(model.state.value, labels).map { it.key },
            )
            model.search("comment")
            assertEquals(
                listOf("a"),
                visibleBookImportItems(model.state.value, labels).map { it.key },
            )
        }

    @Test
    fun querySelectionExpansionAndGroupDraftRestoreWithoutReparsing() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.select("b", true)
            first.expand("a")
            first.search("comment")
            first.openGroup()
            first.groupDraft("unsaved")
            first.addGroupDraft(true)
            val second = model(repo, copy(saved))
            runCurrent()
            assertEquals(1, repo.loads)
            assertEquals(setOf("a", "b"), second.state.value.selected)
            assertEquals(setOf("a"), second.state.value.expanded)
            assertEquals("comment", second.state.value.query)
            assertTrue(second.state.value.groupOpen)
            assertEquals("unsaved", second.state.value.groupDraft)
            assertTrue(second.state.value.addGroupDraft)
            assertNull(second.state.value.group)
            second.closeGroup()
            second.confirm()
            runCurrent()
            assertNull(repo.insertedGroup)
        }

    @Test
    fun codeRequestUsesStableKeyAndConsumedEffectDoesNotRepeatAfterRestore() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.code("b")
            assertEquals("b", first.state.value.effects.single().key)
            first.consume(first.state.value.effects.single().id)
            val second = model(repo, copy(saved))
            runCurrent()
            assertTrue(second.state.value.effects.isEmpty())
            second.refresh("b", "edited")
            runCurrent()
            assertEquals("edited", second.state.value.items.last().originalJson)
            assertEquals("b", second.state.value.items.last().key)
        }

    @Test
    fun manualChoicesAndExplicitDeselectionSurviveAutomaticRoundTrip() =
        runTest(dispatcher) {
            val model = model(Fake())
            runCurrent()
            model.select("a", false)
            model.refresh(ids = listOf(3, 5))
            runCurrent()
            assertEquals(listOf(3L, 5L), model.selectedManualIds())
            assertTrue(model.state.value.useReplacement)
            model.refresh(automatic = true)
            runCurrent()
            model.manual()
            assertTrue(model.state.value.effects.none { it.action == BookImportAction.Manual })
            model.refresh(automatic = false)
            runCurrent()
            model.manual("a")
            assertEquals(listOf(3L, 5L), model.state.value.effects.last().ids)
            assertFalse("a" in model.state.value.selected)
        }

    @Test
    fun malformedDraftCannotChangeManualRulesAndClearsPreview() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            model.refresh("a", "invalid", listOf(7), openManual = true)
            runCurrent()
            assertEquals("a", model.state.value.items.first().originalJson)
            assertTrue(model.state.value.manualIds.isEmpty())
            assertTrue(model.state.value.effects.none { it.action == BookImportAction.Manual })
            assertEquals("clear", model.state.value.effects.last().text)
            assertEquals(BookImportAction.SyncCode, model.state.value.effects.last().action)
        }

    @Test
    fun replacementFailureRollsBackAutomaticPreferencesAndRetainsDraftForRetry() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            repo.refreshFails = true
            model.refresh(automatic = true)
            runCurrent()
            assertFalse(model.state.value.automatic)
            assertFalse(repo.prefs.automaticReplacement)
            assertEquals(2, model.state.value.items.size)
            assertNotNull(model.state.value.error)
            assertFalse(model.state.value.busy)
            repo.refreshFails = false
            model.refresh(automatic = true)
            runCurrent()
            assertTrue(model.state.value.automatic)
        }

    @Test
    fun queuedCodeReturnWaitsForPreferenceWriteAndThenDrains() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            repo.preferenceGate = CompletableDeferred()
            model.preferences(model.state.value.preferences.copy(keepName = true))
            runCurrent()
            model.refresh("b", "edited")
            runCurrent()
            assertTrue(model.state.value.pendingRefresh)
            assertEquals("b", model.state.value.items.last().originalJson)
            repo.preferenceGate!!.complete(Unit)
            runCurrent()
            assertEquals("edited", model.state.value.items.last().originalJson)
            assertFalse(model.state.value.pendingRefresh)
            assertTrue(model.state.value.preferences.keepName)
        }

    @Test
    fun recreationDuringRefreshReplaysDurableRequestAndNeverImports() =
        runTest(dispatcher) {
            val repo = Fake()
            val requests = Requests()
            val saved = SavedStateHandle()
            val first = model(repo, saved, requests)
            val store = ViewModelStore().apply { put("first", first) }
            runCurrent()
            repo.refreshGate = CompletableDeferred()
            first.refresh("b", "edited")
            runCurrent()
            val restoredState = copy(saved)
            assertNotNull(restoredState.get<String>("pendingRequest"))
            store.clear()
            runCurrent()
            assertTrue(requests.values.isNotEmpty())
            repo.refreshGate = null
            val second = model(repo, restoredState, requests)
            runCurrent()
            assertEquals("edited", second.state.value.items.last().originalJson)
            assertFalse(second.state.value.pendingRefresh)
            assertTrue(repo.inserts.isEmpty())
        }

    @Test
    fun callbackBeforeInitialReadCompletesIsNotLost() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadGate = CompletableDeferred() }
            val model = model(repo)
            runCurrent()
            model.refresh("b", "edited")
            runCurrent()
            assertTrue(model.state.value.loading)
            repo.loadGate!!.complete(Unit)
            runCurrent()
            assertEquals("edited", model.state.value.items.last().originalJson)
            assertFalse(model.state.value.pendingRefresh)
        }

    @Test
    fun groupApplyAndRememberDisableResetOnlyAcceptedState() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            model.openGroup()
            model.groupDraft("alpha,beta")
            model.addGroupDraft(true)
            model.acceptGroup()
            model.preferences(
                model.state.value.preferences.copy(
                    rememberGroup = true,
                    lastGroup = model.state.value.group,
                    lastGroupAdd = true,
                )
            )
            runCurrent()
            assertEquals("alpha,beta", repo.prefs.lastGroup)
            model.preferences(model.state.value.preferences.copy(rememberGroup = false))
            runCurrent()
            assertNull(model.state.value.group)
            assertFalse(model.state.value.addGroup)
        }

    @Test
    fun confirmIsSingleFlightAndCommittedCachePreventsStaleStateReimport() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved)
            runCurrent()
            val stale = copy(saved)
            repo.insertGate = CompletableDeferred()
            model.confirm()
            model.confirm()
            model.cancel()
            runCurrent()
            assertEquals(listOf(setOf("a")), repo.inserts)
            assertFalse(model.state.value.finished)
            repo.insertGate!!.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.finished)
            val restored = model(repo, stale)
            runCurrent()
            restored.confirm()
            assertTrue(restored.state.value.finished)
            assertEquals(1, repo.inserts.size)
        }

    @Test
    fun failedReadAndInsertRemainRetryableAndCancelDoesNotWrite() =
        runTest(dispatcher) {
            val repo = Fake().apply { loadFails = true }
            val model = model(repo)
            runCurrent()
            model.confirm()
            runCurrent()
            assertTrue(repo.inserts.isEmpty())
            assertFalse(model.state.value.interactive)
            repo.loadFails = false
            model.load()
            runCurrent()
            repo.insertFails = true
            model.confirm()
            runCurrent()
            assertFalse(model.state.value.finished)
            assertNotNull(model.state.value.error)
            model.cancel()
            assertTrue(model.state.value.finished)
            assertEquals(1, repo.inserts.size)
        }

    @Test
    fun reimportExistingTargetDefaultsToSelectedAndDeliversPublishedSourceOnce() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model =
                BookImportViewModel(repo, Requests(), saved, "source", labels, "https://b").also {
                    models += it
                }
            runCurrent()
            assertEquals(setOf("a", "b"), model.state.value.selected)
            model.confirm()
            runCurrent()
            assertEquals("published:https://b", model.consumeReaderSource())
            assertNull(model.consumeReaderSource())
            val restored =
                BookImportViewModel(repo, Requests(), copy(saved), "source", labels, "https://b")
                    .also { models += it }
            runCurrent()
            assertTrue(restored.state.value.finished)
            assertNull(restored.consumeReaderSource())
            assertEquals(1, repo.inserts.size)
        }

    @Test
    fun deselectedReimportTargetDoesNotUpdateReaderAndCancelDoesNotPublish() =
        runTest(dispatcher) {
            val repo = Fake()
            val model =
                BookImportViewModel(
                        repo,
                        Requests(),
                        SavedStateHandle(),
                        "source",
                        labels,
                        "https://b",
                    )
                    .also { models += it }
            runCurrent()
            model.select("b", false)
            model.confirm()
            runCurrent()
            assertNull(model.consumeReaderSource())
            assertEquals(listOf(setOf("a")), repo.inserts)
            val cancelled =
                BookImportViewModel(
                        Fake(),
                        Requests(),
                        SavedStateHandle(),
                        "source",
                        labels,
                        "https://b",
                    )
                    .also { models += it }
            runCurrent()
            cancelled.cancel()
            assertNull(cancelled.consumeReaderSource())
        }

    @Test
    fun explorerFiltersAndVisibleNewSelectionDoNotChangeHiddenExistingChoice() =
        runTest(dispatcher) {
            val model = model(Fake())
            runCurrent()
            model.select("b", true)
            model.search("enabled explore")
            assertEquals(
                listOf("a"),
                visibleBookImportItems(model.state.value, labels).map { it.key },
            )
            model.selectStatus(BookImportStatus.New)
            assertEquals(setOf("b"), model.state.value.selected)
            model.search("disabled explore")
            assertEquals(
                listOf("b"),
                visibleBookImportItems(model.state.value, labels).map { it.key },
            )
            model.selectStatus(BookImportStatus.New)
            assertEquals(setOf("b"), model.state.value.selected)
        }

    private fun entry(key: String, json: String = key) =
        BookImportEntry(
            key,
            json,
            json,
            null,
            null,
            emptyList(),
            null,
            key,
            "https://$key",
            if (key == "a") null else "group",
            if (key == "a") "comment" else null,
            key == "a",
            if (key == "b") "login" else null,
            key == "a",
            key == "b",
            if (key == "a") BookImportStatus.New else BookImportStatus.Existing,
            true,
            key == "a",
        )

    private inner class Fake : BookImportRepository {
        var cache: BookImportSnapshot? = null
        var prefs = BookImportPreferences()
        var loads = 0
        var loadFails = false
        var refreshFails = false
        var insertFails = false
        var loadGate: CompletableDeferred<Unit>? = null
        var refreshGate: CompletableDeferred<Unit>? = null
        var preferenceGate: CompletableDeferred<Unit>? = null
        var insertGate: CompletableDeferred<Unit>? = null
        val inserts = mutableListOf<Set<String>>()
        var insertedGroup: String? = null

        override suspend fun load(source: String): List<BookImportOriginal> {
            loads++
            loadGate?.await()
            if (loadFails) error("format")
            return listOf(BookImportOriginal("a", "a"), BookImportOriginal("b", "b"))
        }

        override suspend fun refresh(
            originals: List<BookImportOriginal>,
            automatic: Boolean,
            manualIds: Map<String, List<Long>>,
        ): List<BookImportEntry> {
            refreshGate?.await()
            if (refreshFails) error("refresh")
            return originals.map {
                entry(it.key, it.json).copy(effectiveRuleIds = manualIds[it.key].orEmpty())
            }
        }

        override suspend fun parseEdited(key: String, code: String): BookImportOriginal {
            if (code == "invalid") error("format")
            return BookImportOriginal(key, code)
        }

        override suspend fun source(url: String) = "published:$url"

        override suspend fun groups() = listOf("alpha", "beta")

        override suspend fun preferences() = prefs

        override suspend fun preferences(value: BookImportPreferences) {
            preferenceGate?.await()
            prefs = value
        }

        override suspend fun restore(session: String) = cache

        override suspend fun stage(session: String, snapshot: BookImportSnapshot) {
            cache = snapshot
        }

        override suspend fun insert(
            session: String,
            snapshot: BookImportSnapshot,
            selected: Set<String>,
            preferences: BookImportPreferences,
            group: String?,
            addGroup: Boolean,
        ) {
            inserts += selected.toSet()
            insertedGroup = group
            insertGate?.await()
            if (insertFails) error("disk")
            cache = snapshot.copy(committed = true)
        }
    }

    private class Requests : BookImportRequestRepository {
        val values = mutableMapOf<String, BookImportRefreshRequest>()
        private var next = 0

        override suspend fun write(request: BookImportRefreshRequest): String =
            (++next).toString().also { values[it] = request }

        override suspend fun read(id: String) = values[id]

        override suspend fun remove(id: String) {
            values.remove(id)
        }
    }
}
