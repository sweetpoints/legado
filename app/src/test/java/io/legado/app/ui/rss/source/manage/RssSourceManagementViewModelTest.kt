package io.legado.app.ui.rss.source.manage

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssSourceManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssSourceManagementViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val labels = RssSourceManagementLabels("Enabled", "Disabled", "Login", "No group")

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Repo : RssSourceManagementRepository {
        val values =
            MutableStateFlow(
                (0..4).map {
                    RssSourceManagementRow("id-$it", "Name-$it", "Name-$it", null, true, it)
                }
            )
        val groupValues = MutableStateFlow(listOf("A", "B"))
        var filter: RssSourceManagementFilter? = null
        val enabled = mutableListOf<Pair<List<String>, Boolean>>()
        val moves = mutableListOf<Triple<String, String, Boolean>>()
        val deleted = mutableListOf<List<String>>()
        val groups = mutableListOf<Triple<List<String>, String, Boolean>>()
        val imports = mutableListOf<String>()
        val released = mutableListOf<String>()
        val releaseDone = CompletableDeferred<Unit>()
        var exportGate: CompletableDeferred<Unit>? = null
        var exports = 0
        var gate: CompletableDeferred<Unit>? = null

        override fun rows(filter: RssSourceManagementFilter) = values.also { this.filter = filter }

        override fun groups() = groupValues

        override suspend fun source(id: String) = RssSource(sourceUrl = id)

        override suspend fun enabled(ids: List<String>, enabled: Boolean) {
            this.enabled += ids to enabled
            withContext(NonCancellable) { gate?.await() }
        }

        override suspend fun group(ids: List<String>, value: String, add: Boolean) {
            groups += Triple(ids, value, add)
        }

        override suspend fun edge(ids: List<String>, top: Boolean) {}

        override suspend fun move(id: String, target: String, after: Boolean) {
            moves += Triple(id, target, after)
        }

        override suspend fun delete(ids: List<String>) {
            deleted += ids
        }

        override suspend fun export(ids: List<String>): RssSourceManagementExport {
            exports++
            withContext(NonCancellable) { exportGate?.await() }
            return RssSourceManagementExport("owned-$exports", "full.json")
        }

        override suspend fun releaseExport(path: String) {
            released += path
            releaseDone.complete(Unit)
        }

        override suspend fun importDefault() {}

        override suspend fun importHistory() = listOf("https://previous.invalid")

        override suspend fun rememberImport(value: String) {
            imports += value
        }

        override suspend fun forgetImport(value: String) {}
    }

    private class Sessions : RssSourceManagementSessionRepository {
        var value: RssSourceManagementCheckpoint? = null
        var failure = false
        var readGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<RssSourceManagementCheckpoint>()

        override suspend fun read(session: String): RssSourceManagementCheckpoint? {
            withContext(NonCancellable) { readGate?.await() }
            return value
        }

        override suspend fun write(session: String, value: RssSourceManagementCheckpoint) {
            if (failure) error("Disk failure")
            writes += value
            if (value.revision >= (this.value?.revision ?: -1)) this.value = value
        }

        override suspend fun release(session: String) {}
    }

    private class Sharing : RssSourceManagementSharingRepository {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun feedback(url: String): RssSourceManagementShareFeedback {
            calls++
            withContext(NonCancellable) { gate?.await() }
            return RssSourceManagementShareFeedback(url, "Expiry summary", true)
        }

        override suspend fun passphrase(url: String) = "RSS passphrase:$url"
    }

    private fun model(
        repo: Repo = Repo(),
        sessions: Sessions = Sessions(),
        saved: SavedStateHandle = SavedStateHandle(),
        sharing: Sharing = Sharing(),
    ) = RssSourceManagementViewModel(repo, sessions, saved, sharing).also { models += it }

    private fun clone(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun gate() = CompletableDeferred<Unit>().also { gates += it }

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                gates.forEach { it.complete(Unit) }
                runCurrent()
            }
        }

    @Test
    fun hugeQueryAndImportDraftRestoreWithSelectionAndOnlySmallSavedState() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val first = model(sessions = sessions, saved = saved)
        first.bind(labels)
        runCurrent()
        first.query("Q".repeat(2000000), 7, 9)
        first.selected("id-2", true)
        first.dialog(RssSourceManagementDialog.ImportUrl)
        first.draft("U".repeat(2000000), 12, 20)
        first.scroll(3, 14)
        runCurrent()
        first.stop()
        val restored = model(sessions = sessions, saved = clone(saved))
        restored.bind(labels)
        runCurrent()
        assertEquals(2000000, restored.state.value.query.length)
        assertEquals(2000000, restored.state.value.draft.length)
        assertEquals(7, restored.state.value.queryStart)
        assertEquals(20, restored.state.value.draftEnd)
        assertEquals(setOf("id-2"), restored.state.value.selected)
        assertEquals(3, restored.state.value.scrollIndex)
        assertEquals(listOf("https://previous.invalid"), restored.state.value.history)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
    }

    @Test
    fun savedRevisionLagCannotRejectFirstEditAfterRestore() = test {
        val sessions =
            Sessions().apply {
                value = RssSourceManagementCheckpoint(revision = 70, query = "Previous")
            }
        val model =
            model(
                sessions = sessions,
                saved = SavedStateHandle(mapOf("rssManagement.revision" to 3L)),
            )
        model.bind(labels)
        runCurrent()
        model.query("New")
        runCurrent()
        assertEquals(71L, sessions.value!!.revision)
        assertEquals("New", sessions.value!!.query)
    }

    @Test
    fun localizedSpecialQueriesAndExactGroupPrefixUseTypedFilters() = test {
        val repo = Repo()
        val model = model(repo)
        model.bind(labels)
        runCurrent()
        listOf(
                "" to RssSourceManagementFilter.All,
                "Enabled" to RssSourceManagementFilter.Enabled,
                "Disabled" to RssSourceManagementFilter.Disabled,
                "Login" to RssSourceManagementFilter.Login,
                "No group" to RssSourceManagementFilter.NoGroup,
                "group:A" to RssSourceManagementFilter.Group("A"),
                "Other" to RssSourceManagementFilter.Search("Other"),
            )
            .forEach { (query, filter) ->
                model.query(query)
                runCurrent()
                assertEquals(filter, repo.filter)
            }
    }

    @Test
    fun selectionIntervalWithoutSelectedRowsIsNoOpAndHiddenSelectionsStayPrivate() = test {
        val repo = Repo()
        val model = model(repo)
        model.bind(labels)
        runCurrent()
        model.selectInterval()
        assertTrue(model.state.value.selected.isEmpty())
        model.selected("id-1", true)
        model.selected("id-3", true)
        model.selectInterval()
        runCurrent()
        assertEquals(setOf("id-1", "id-2", "id-3"), model.state.value.selected)
        repo.values.value = repo.values.value.filter { it.id == "id-4" }
        runCurrent()
        assertTrue(model.state.value.visibleSelection.isEmpty())
        assertEquals(3, model.state.value.selected.size)
        model.effect(RssSourceManagementAction.Export)
        runCurrent()
        assertEquals(0, repo.exports)
    }

    @Test
    fun slidingSelectionUsesBaselineAndCancellationNeverWritesTransientSelection() = test {
        val sessions = Sessions()
        val model = model(sessions = sessions)
        model.bind(labels)
        runCurrent()
        model.selected("id-1", true)
        runCurrent()
        val count = sessions.writes.size
        assertTrue(model.beginSelection())
        model.selectionRange("id-1", "id-3")
        assertEquals(setOf("id-2", "id-3"), model.state.value.selected)
        model.selectionRange("id-1", "id-2")
        assertEquals(setOf("id-2"), model.state.value.selected)
        model.cancelGesture()
        runCurrent()
        assertEquals(setOf("id-1"), model.state.value.selected)
        assertEquals(count, sessions.writes.size)
        model.beginSelection()
        model.selectionRange("id-1", "id-3")
        model.finishSelection()
        runCurrent()
        assertEquals(listOf("id-2", "id-3"), sessions.value!!.selected)
    }

    @Test
    fun cancelledReorderBuffersRoomRowsAndCompletedGestureMovesExactlyOnce() = test {
        val repo = Repo()
        val model = model(repo)
        model.bind(labels)
        runCurrent()
        assertTrue(model.beginDrag("id-0"))
        model.dragTo("id-3", true)
        repo.values.value = repo.values.value.map { it.copy(name = "Latest") }
        runCurrent()
        model.cancelGesture()
        assertTrue(model.state.value.rows.all { it.name == "Latest" })
        assertTrue(repo.moves.isEmpty())
        model.beginDrag("id-0")
        model.dragTo("id-3", true)
        model.finishDrag()
        model.finishDrag()
        runCurrent()
        assertEquals(listOf(Triple("id-0", "id-3", true)), repo.moves)
    }

    @Test
    fun cancelGroupDraftDoesNotMutateAndConfirmUsesFrozenTargets() = test {
        val repo = Repo()
        val model = model(repo)
        model.bind(labels)
        runCurrent()
        model.selected("id-1", true)
        model.dialog(RssSourceManagementDialog.AddGroup)
        model.draft("A")
        model.cancelDialog()
        runCurrent()
        assertTrue(repo.groups.isEmpty())
        model.dialog(RssSourceManagementDialog.RemoveGroup)
        model.draft("A")
        model.confirmDialog()
        model.confirmDialog()
        runCurrent()
        assertEquals(listOf(Triple(listOf("id-1"), "A", false)), repo.groups)
        assertNull(model.state.value.dialog)
    }

    @Test
    fun nativePreparedExportRestoresAndDeliversOnceWithoutRegeneratingFile() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val repo = Repo()
        val first = model(repo, sessions, saved)
        first.bind(labels)
        runCurrent()
        first.selected("id-1", true)
        first.effect(RssSourceManagementAction.Export)
        runCurrent()
        val effect = first.state.value.pending!!
        assertEquals("owned-1", first.native(effect.nonce)!!.export!!.path)
        first.stop()
        val restored = model(repo, sessions, clone(saved))
        restored.bind(labels)
        runCurrent()
        assertEquals(effect, restored.state.value.pending)
        assertTrue(restored.delivered(effect.nonce))
        assertFalse(restored.delivered(effect.nonce))
        runCurrent()
        assertEquals(1, repo.exports)
    }

    @Test
    fun preparedCheckpointFailureRetriesExactReceiptWithoutRepeatingExport() = test {
        val repo = Repo()
        val sessions = Sessions()
        val model = model(repo, sessions)
        model.bind(labels)
        runCurrent()
        model.selected("id-1", true)
        runCurrent()
        sessions.failure = true
        model.effect(RssSourceManagementAction.Share)
        runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.pending)
        sessions.failure = false
        model.retry()
        runCurrent()
        assertEquals(1, repo.exports)
        assertEquals("owned-1", model.native(model.state.value.pending!!.nonce)!!.export!!.path)
    }

    @Test
    fun cancelledNonCooperativeExportIsReleasedBeforeAnyCheckpointCanClaimIt() = test {
        val repo = Repo().apply { exportGate = gate() }
        val sessions = Sessions()
        val model = model(repo, sessions)
        model.bind(labels)
        runCurrent()
        model.selected("id-1", true)
        runCurrent()
        model.effect(RssSourceManagementAction.Share)
        runCurrent()
        model.stop()
        repo.exportGate!!.complete(Unit)
        runCurrent()
        repo.releaseDone.await()
        assertEquals(listOf("owned-1"), repo.released)
        assertNull(model.state.value.pending)
        assertNull(sessions.value!!.pending)
    }

    @Test
    fun nonCooperativeReadAndMutationCannotPublishAfterOwnerStop() = test {
        val sessions = Sessions().apply { readGate = gate() }
        val blocked = model(sessions = sessions)
        blocked.bind(labels)
        runCurrent()
        blocked.stop()
        sessions.readGate!!.complete(Unit)
        runCurrent()
        assertFalse(blocked.state.value.loaded)
        val repo = Repo().apply { gate = gate() }
        val loaded = model(repo)
        loaded.bind(labels)
        runCurrent()
        loaded.enabled(listOf("id-0"), false)
        runCurrent()
        loaded.stop()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertTrue(loaded.state.value.busy)
        assertEquals(1, repo.enabled.size)
    }

    @Test
    fun reorderBackToOriginalKeepsRawDaoOrderUntouched() = test {
        val repo = Repo()
        val model = model(repo)
        model.bind(labels)
        runCurrent()
        model.beginDrag("id-0")
        model.dragTo("id-3", true)
        model.dragTo("id-1", false)
        model.finishDrag()
        runCurrent()
        assertTrue(repo.moves.isEmpty())
        assertEquals(repo.values.value, model.state.value.rows)
    }

    private fun TestScope.launch(
        model: RssSourceManagementViewModel,
        action: RssSourceManagementAction,
    ): String {
        model.effect(action)
        runCurrent()
        val nonce = model.state.value.pending!!.nonce
        assertTrue(model.delivered(nonce))
        runCurrent()
        return nonce
    }

    @Test
    fun localReturnRejectsOldNoncePersistsLargeInputAndNeverAddsUrlHistory() = test {
        val repo = Repo()
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val model = model(repo, sessions, saved)
        model.bind(labels)
        runCurrent()
        val nonce = launch(model, RssSourceManagementAction.ImportLocal)
        model.returned("stale", "wrong")
        runCurrent()
        assertNull(model.state.value.pending)
        val input = "content://" + "A".repeat(2000000)
        model.returned(nonce, input)
        runCurrent()
        val pending = model.state.value.pending!!
        assertEquals(RssSourceManagementAction.ImportInput, pending.action)
        assertEquals(input, model.native(pending.nonce)!!.input)
        assertTrue(repo.imports.isEmpty())
        assertNull(model.waiting(RssSourceManagementAction.ImportLocal))
        model.returned(nonce, "duplicate")
        runCurrent()
        assertEquals(input, model.native(pending.nonce)!!.input)
        assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
        model.stop()
        val restored = model(repo, sessions, clone(saved))
        restored.bind(labels)
        runCurrent()
        assertEquals(input, restored.native(pending.nonce)!!.input)
        assertTrue(restored.delivered(pending.nonce))
        assertFalse(restored.delivered(pending.nonce))
    }

    @Test
    fun canceledPickerAndFailedLaunchReleaseWaitingTicketAndAllowNextRequest() = test {
        val model = model()
        model.bind(labels)
        runCurrent()
        val first = launch(model, RssSourceManagementAction.ImportQr)
        model.returned(first, null)
        runCurrent()
        assertFalse(model.state.value.waitingNative)
        val second = launch(model, RssSourceManagementAction.ImportLocal)
        assertNotEquals(first, second)
        model.returned(first, "late QR")
        runCurrent()
        assertEquals(second, model.waiting(RssSourceManagementAction.ImportLocal))
    }

    @Test
    fun earlyPickerReturnWaitsForRestoredDiskBeforeImportAndIsConsumedOnce() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val first = model(sessions = sessions, saved = saved)
        first.bind(labels)
        runCurrent()
        val nonce = launch(first, RssSourceManagementAction.ImportLocal)
        first.stop()
        sessions.readGate = gate()
        val restored = model(sessions = sessions, saved = clone(saved))
        restored.bind(labels)
        runCurrent()
        restored.returned(nonce, "content://restored")
        assertFalse(restored.state.value.loaded)
        sessions.readGate!!.complete(Unit)
        runCurrent()
        val pending = restored.state.value.pending!!
        assertEquals("content://restored", restored.native(pending.nonce)!!.input)
        assertFalse(restored.state.value.waitingNative)
    }

    @Test
    fun failedReturnedWriteKeepsSamePayloadAndWaitingUntilRetrySucceeds() = test {
        val sessions = Sessions()
        val model = model(sessions = sessions)
        model.bind(labels)
        runCurrent()
        val nonce = launch(model, RssSourceManagementAction.ImportLocal)
        sessions.failure = true
        model.returned(nonce, "content://large-document")
        runCurrent()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.pending)
        assertEquals(nonce, model.waiting(RssSourceManagementAction.ImportLocal))
        sessions.failure = false
        model.retry()
        runCurrent()
        val pending = model.state.value.pending!!
        assertEquals("content://large-document", model.native(pending.nonce)!!.input)
        assertFalse(model.state.value.waitingNative)
    }

    @Test
    fun exportFeedbackAndPassphraseRestoreAndCopyCapturedOriginalRatherThanEditedDisplay() = test {
        val saved = SavedStateHandle()
        val sessions = Sessions()
        val sharing = Sharing()
        val model = model(sessions = sessions, saved = saved, sharing = sharing)
        model.bind(labels)
        runCurrent()
        model.selected("id-0", true)
        val nonce = launch(model, RssSourceManagementAction.Export)
        model.returned(nonce, "https://export.invalid/rss")
        runCurrent()
        assertEquals(RssSourceManagementDialog.ExportResult, model.state.value.dialog)
        assertEquals("Expiry summary", model.state.value.feedback!!.summary)
        model.passphrase()
        runCurrent()
        model.draft("display only", 3, 4)
        runCurrent()
        model.stop()
        val restored = model(sessions = sessions, saved = clone(saved), sharing = sharing)
        restored.bind(labels)
        runCurrent()
        assertEquals(RssSourceManagementDialog.Passphrase, restored.state.value.dialog)
        assertEquals("display only", restored.state.value.draft)
        assertEquals(4, restored.state.value.draftEnd)
        restored.copyFeedback()
        runCurrent()
        val pending = restored.state.value.pending!!
        assertEquals(
            "RSS passphrase:https://export.invalid/rss",
            restored.native(pending.nonce)!!.input,
        )
        assertEquals(1, sharing.calls)
    }

    @Test
    fun pausedNonCooperativeExportFeedbackCannotPublishAfterOwnerStops() = test {
        val sharing = Sharing().apply { gate = gate() }
        val model = model(sharing = sharing)
        model.bind(labels)
        runCurrent()
        model.selected("id-0", true)
        val nonce = launch(model, RssSourceManagementAction.Export)
        model.returned(nonce, "https://export.invalid")
        runCurrent()
        model.stop()
        sharing.gate!!.complete(Unit)
        runCurrent()
        assertNull(model.state.value.dialog)
        assertEquals(nonce, model.waiting(RssSourceManagementAction.Export))
    }

    @Test
    fun pickerReturnDuringInFlightMutationWaitsAndKeepsFirstMatchingPayload() = test {
        val repo = Repo().apply { gate = gate() }
        val model = model(repo)
        model.bind(labels)
        runCurrent()
        val nonce = launch(model, RssSourceManagementAction.ImportLocal)
        model.enabled(listOf("id-0"), false)
        runCurrent()
        model.returned(nonce, "content://first")
        model.returned(nonce, "content://duplicate")
        runCurrent()
        assertNull(model.state.value.pending)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals("content://first", model.native(model.state.value.pending!!.nonce)!!.input)
    }
}
