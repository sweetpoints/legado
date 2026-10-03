package io.legado.app.ui.autoTask

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoTaskManagementViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AutoTaskManagementViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        models.forEach {
            it.stop()
            it.viewModelScope.cancel()
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        AutoTaskManagementViewModel(repo, saved).also { models += it }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun nameSearchTrimsIgnoresCaseAndRemembersHiddenSelectionButBatchesOnlyVisibleIds() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.select("a")
            vm.select("b")
            vm.query("  ALPHA  ")
            assertEquals(listOf("a"), vm.state.value.visible.map { it.id })
            assertEquals(listOf("a"), vm.state.value.selection.map { it.id })
            vm.selectedEnabled(false)
            runCurrent()
            assertEquals(listOf("a") to false, repo.enabled.single())
            vm.query("")
            assertEquals(setOf("a", "b"), vm.state.value.selected)
            vm.query("cron")
            assertTrue(vm.state.value.visible.isEmpty())
        }

    @Test
    fun selectAllAndInvertAffectOnlyCurrentVisibleSubsetAndDeletedIdsArePruned() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.select("b")
            vm.query("Alpha")
            vm.selectAll()
            assertEquals(setOf("a", "b"), vm.state.value.selected)
            vm.selectAll()
            assertEquals(setOf("b"), vm.state.value.selected)
            vm.invert()
            assertEquals(setOf("a", "b"), vm.state.value.selected)
            repo.rows.value = repo.rows.value.filterNot { it.id == "b" }
            runCurrent()
            assertEquals(setOf("a"), vm.state.value.selected)
        }

    @Test
    fun toggleAndReverseUsesAnchorStateAndRestoresMixedBaselineOnCancelOrRecreation() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.select("b")
            vm.beginSlide("a")
            vm.slideTo("c")
            assertEquals(setOf("a", "b", "c"), vm.state.value.selected)
            vm.slideTo("a")
            assertEquals(setOf("a"), vm.state.value.selected)
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertEquals(setOf("b"), restored.state.value.selected)
            vm.cancelSlide()
            assertEquals(setOf("b"), vm.state.value.selected)
            assertTrue(repo.orders.isEmpty())
            vm.beginSlide("b")
            vm.slideTo("c")
            vm.endSlide()
            assertTrue(vm.state.value.selected.isEmpty())
        }

    @Test
    fun movingFilteredRowsSuppliesOnlyVisibleOrderAndRejectsOutOfBounds() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.rows.value =
                listOf(item("a", "same one"), item("b", "hidden"), item("c", "same two"))
            val vm = model(repo)
            runCurrent()
            vm.query("same")
            vm.move("a", -1)
            vm.move("a", 1)
            runCurrent()
            assertEquals(listOf(listOf("c", "a")), repo.orders)
        }

    @Test
    fun batchCronCapturesOriginalTargetsRestoresDraftAndKeepsInvalidOrFailedInput() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.select("a")
            vm.select("b")
            vm.openCron()
            vm.cronDraft("bad cron")
            vm.saveCron()
            assertTrue(vm.state.value.cronInvalid)
            assertTrue(repo.crons.isEmpty())
            vm.cronDraft("  0 * * * *  ")
            vm.query("Alpha")
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertEquals(listOf("a", "b"), restored.state.value.cronIds)
            repo.failMutation = true
            restored.saveCron()
            runCurrent()
            assertNotNull(restored.state.value.cronIds)
            assertEquals("  0 * * * *  ", restored.state.value.cronDraft)
            repo.failMutation = false
            restored.saveCron()
            runCurrent()
            assertEquals(listOf("a", "b") to "0 * * * *", repo.crons.single())
            assertNull(restored.state.value.cronIds)
        }

    @Test
    fun deleteConfirmationCancelDoesNotWriteAndFailureKeepsCapturedTargets() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.select("a")
            vm.askDelete()
            vm.closeDelete()
            assertTrue(repo.deleted.isEmpty())
            vm.askDelete()
            vm.query("Beta")
            repo.failMutation = true
            vm.delete()
            runCurrent()
            assertEquals(listOf("a"), vm.state.value.deleteIds)
            repo.failMutation = false
            vm.delete()
            runCurrent()
            assertEquals(listOf(listOf("a")), repo.deleted)
            assertNull(vm.state.value.deleteIds)
        }

    @Test
    fun loginRequiresCapabilityAndConsumedHostEffectDoesNotRepeatAfterRestore() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.action(AutoTaskManagementAction.Login, "a")
            assertTrue(vm.state.value.effects.isEmpty())
            vm.action(AutoTaskManagementAction.Login, "b")
            val event = vm.state.value.effects.single()
            assertEquals("b", event.value)
            vm.consume(event.id)
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertTrue(restored.state.value.effects.isEmpty())
            restored.showLog("a")
            restored.clearLog()
            runCurrent()
            assertEquals(listOf("a"), repo.cleared)
            assertNull(restored.state.value.logId)
        }

    @Test
    fun largeOnlineDraftRestoresFromRepositoryAndNeverEntersBundle() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.openOnline()
            runCurrent()
            val text = "{\"script\":\"" + "large".repeat(100000) + "\"}"
            vm.onlineInput(text)
            runCurrent()
            vm.flushDraft()
            assertTrue(
                saved.keys().all {
                    (saved.get<Any?>(it) as? String)?.length?.let { it < 1000 } != false
                }
            )
            val restored = model(repo, snapshot(saved))
            runCurrent()
            assertTrue(restored.state.value.online)
            assertEquals(text, restored.state.value.onlineInput)
            restored.closeOnline()
            assertTrue(repo.remembered.isEmpty())
            restored.openOnline()
            runCurrent()
            assertEquals("", restored.state.value.onlineInput)
        }

    @Test
    fun onlineRestoreAndHistoryCannotOverwriteEarlyTypingAndImportIsDiskBacked() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.openOnline()
            runCurrent()
            first.onlineInput("old")
            runCurrent()
            repo.historyGate = CompletableDeferred()
            val restored = model(repo, snapshot(saved))
            runCurrent()
            restored.onlineInput("  https://new.invalid  ")
            repo.historyGate!!.complete(Unit)
            runCurrent()
            assertEquals("  https://new.invalid  ", restored.state.value.onlineInput)
            restored.importOnline()
            runCurrent()
            assertEquals(listOf("https://new.invalid"), repo.remembered)
            val event = restored.state.value.effects.single()
            assertEquals(AutoTaskManagementAction.ImportDraft, event.action)
            assertEquals("https://new.invalid", restored.importText(event.value!!))
            assertFalse(restored.state.value.online)
        }

    @Test
    fun historyDeleteAndEmptyOnlineInputDoNotLaunchChild() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.openOnline()
            runCurrent()
            vm.importOnline()
            assertTrue(vm.state.value.effects.isEmpty())
            assertEquals("EmptyImport", vm.state.value.error)
            vm.removeHistory("https://old.invalid")
            runCurrent()
            assertTrue(vm.state.value.history.isEmpty())
            assertEquals(listOf("https://old.invalid"), repo.removed)
        }

    @Test
    fun exportSelectionUsesVisibleIdsAndSavedEffectContainsOnlyTicket() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.select("a")
            vm.select("b")
            vm.query("Alpha")
            vm.export(true)
            runCurrent()
            assertEquals(listOf("a"), repo.exportIds.single())
            val event = vm.state.value.effects.single()
            assertEquals(AutoTaskManagementAction.Export, event.action)
            assertEquals("json payload", vm.exportText(event))
            assertFalse(saved.get<String>("autoTask.management.effects")!!.contains("json payload"))
            vm.consume(event.id)
            vm.releaseExport(event)
            assertEquals(listOf("ticket"), repo.released)
            vm.export(false)
            runCurrent()
            assertNull(repo.exportIds.last())
        }

    @Test
    fun exportNoticeWaitsForLatestRequestAndDismissCannotBeUndoneByLateIo() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            repo.noticeGate = CompletableDeferred()
            vm.exportReturned("https://old.invalid")
            runCurrent()
            vm.closeExportNotice()
            repo.noticeGate!!.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.exportNotice)
            repo.noticeGate = null
            vm.exportReturned("https://new.invalid")
            runCurrent()
            vm.copyExport(true)
            assertEquals("passphrase:https://new.invalid", vm.state.value.effects.single().value)
            assertNotNull(vm.state.value.exportNotice)
            vm.copyExport()
            assertNull(vm.state.value.exportNotice)
            assertEquals("https://new.invalid", vm.state.value.effects.last().value)
        }

    @Test
    fun roomErrorShowsFailureAndRetryResubscribesWithoutClearingSelection() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.failObserve = true
            val vm = model(repo)
            runCurrent()
            assertEquals("read failed", vm.state.value.error)
            assertFalse(vm.state.value.loading)
            repo.failObserve = false
            vm.observe()
            runCurrent()
            vm.select("a")
            assertEquals(setOf("a"), vm.state.value.selected)
        }

    private fun item(id: String, name: String) =
        AutoTaskListItem(id, name, true, "cron", "summary", id == "b", "log")

    private inner class Fake : AutoTaskManagementRepository {
        val rows =
            MutableStateFlow(listOf(item("a", "Alpha"), item("b", "Beta"), item("c", "Charlie")))
        val enabled = mutableListOf<Pair<List<String>, Boolean>>()
        val crons = mutableListOf<Pair<List<String>, String>>()
        val deleted = mutableListOf<List<String>>()
        val orders = mutableListOf<List<String>>()
        val cleared = mutableListOf<String>()
        val drafts = mutableMapOf<String, AutoTaskOnlineDraft>()
        val remembered = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val exportIds = mutableListOf<List<String>?>()
        val released = mutableListOf<String>()
        var failMutation = false
        var failObserve = false
        var historyGate: CompletableDeferred<Unit>? = null
        var noticeGate: CompletableDeferred<Unit>? = null

        override fun observe(): Flow<List<AutoTaskListItem>> =
            if (failObserve) flow { error("read failed") } else rows

        private fun check() {
            if (failMutation) error("write failed")
        }

        override suspend fun enabled(ids: List<String>, value: Boolean) {
            check()
            enabled += ids to value
        }

        override suspend fun cron(ids: List<String>, value: String) {
            check()
            crons += ids to value
        }

        override suspend fun delete(ids: List<String>) {
            check()
            deleted += ids
        }

        override suspend fun reorder(ids: List<String>) {
            check()
            orders += ids
        }

        override suspend fun clearLog(id: String) {
            cleared += id
        }

        override suspend fun history(): List<String> {
            withContext(NonCancellable) { historyGate?.await() }
            return listOf("https://old.invalid")
        }

        override suspend fun remember(url: String) {
            remembered += url
        }

        override suspend fun removeHistory(url: String) {
            removed += url
        }

        override suspend fun export(ids: List<String>?): AutoTaskExportTicket {
            exportIds += ids
            return AutoTaskExportTicket("ticket", "export.json")
        }

        override suspend fun exportText(ticket: AutoTaskExportTicket) = "json payload"

        override suspend fun releaseExport(ticket: AutoTaskExportTicket) {
            released += ticket.id
        }

        override suspend fun exportNotice(url: String): AutoTaskExportNotice {
            withContext(NonCancellable) { noticeGate?.await() }
            return AutoTaskExportNotice(url, "summary", "passphrase:$url")
        }

        override suspend fun readDraft(session: String) = drafts[session]

        override suspend fun writeDraft(session: String, draft: AutoTaskOnlineDraft) {
            if ((drafts[session]?.revision ?: -1) <= draft.revision) drafts[session] = draft
        }
    }
}
