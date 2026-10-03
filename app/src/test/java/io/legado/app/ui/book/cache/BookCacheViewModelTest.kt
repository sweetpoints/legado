package io.legado.app.ui.book.cache

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BookCacheViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookCacheViewModel>()
    private val repos = mutableListOf<Fake>()

    @Before
    fun before() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun after() {
        models.forEach {
            it.stop()
            it.viewModelScope.cancel()
        }
        repos.forEach {
            it.scanGate?.complete(Unit)
            it.previewGate?.complete(Unit)
            it.stageGate?.complete(Unit)
            it.preferenceGate?.complete(Unit)
            it.preferenceReadGate?.complete(Unit)
            it.exportGate?.complete(Unit)
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        BookCacheViewModel(repo, saved).also {
            models += it
            repos += repo
        }

    private fun snapshot(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    @Test
    fun flowRowsAreScannedAndSavedContentIsDeduplicatedIncludingDuringScan() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.scanGate = CompletableDeferred()
            val vm = model(repo)
            runCurrent()
            assertNull(vm.state.value.rows.single().cached)
            vm.chapterSaved("a", "new")
            vm.chapterSaved("a", "new")
            repo.scanGate!!.complete(Unit)
            runCurrent()
            assertEquals(setOf("old", "new"), vm.state.value.rows.single().cached)
            assertEquals(8, vm.state.value.rows.single().total)
            vm.chapterSaved("a", "old")
            assertEquals(2, vm.state.value.rows.single().cached!!.size)
        }

    @Test
    fun canceledNonCooperativeScanCannotOverwriteNewGroupOrStoppedState() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.scanGate = CompletableDeferred()
            val vm = model(repo)
            runCurrent()
            vm.group(2)
            runCurrent()
            assertEquals("b", vm.state.value.rows.single().book.key)
            repo.scanGate!!.complete(Unit)
            runCurrent()
            assertEquals("b", vm.state.value.rows.single().book.key)
            assertEquals(setOf("old"), vm.state.value.rows.single().cached)
            vm.stop()
            val state = vm.state.value
            repo.second.value = emptyList()
            runCurrent()
            assertEquals(state, vm.state.value)
        }

    @Test
    fun globalDownloadRequiresConfirmationAndStopDoesNotRequireIt() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.download(true)
            assertTrue(repo.downloads.isEmpty())
            assertEquals(true, vm.state.value.confirmAfterCurrent)
            vm.cancelDownloadConfirmation()
            vm.confirmDownload()
            runCurrent()
            assertTrue(repo.downloads.isEmpty())
            vm.download(false)
            vm.confirmDownload()
            runCurrent()
            assertEquals(listOf(listOf("a") to false), repo.downloads)
            repo.runtimeValue = repo.runtimeValue.copy(running = true)
            vm.refresh()
            runCurrent()
            vm.download(true)
            runCurrent()
            assertEquals(1, repo.stops)
            assertNull(vm.state.value.confirmAfterCurrent)
            vm.toggleDownload("a")
            runCurrent()
            assertEquals(listOf("a"), repo.toggles)
        }

    @Test
    fun runtimeUpdatesMessagesAndProgressAndPreferencesWriteInUserOrder() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.preferences { it.copy(type = 2, charset = "GBK") }
            vm.preferences { it.copy(replace = true) }
            runCurrent()
            assertEquals(listOf(false, true), repo.writes.map { it.replace })
            assertEquals("pdf", repo.writes.last().exportType)
            repo.runtimeValue =
                BookCacheRuntime(true, setOf("a"), mapOf("a" to 3), mapOf("a" to "done"))
            vm.refresh()
            runCurrent()
            assertTrue(vm.state.value.rows.single().downloading)
            assertEquals(3, vm.state.value.rows.single().progress)
            assertEquals("done", vm.state.value.rows.single().message)
        }

    @Test
    fun exportWaitsForSerializedPreferenceWritesAndOnlyChangedFieldsAreWritten() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.path = "path"
            repo.preferenceGate = CompletableDeferred()
            val vm = model(repo)
            runCurrent()
            vm.preferences { it.copy(type = 2) }
            vm.preferences { it.copy(replace = true) }
            vm.export("a")
            runCurrent()
            assertTrue(repo.exports.isEmpty())
            assertTrue(repo.tickets.isEmpty())
            repo.preferenceGate!!.complete(Unit)
            runCurrent()
            assertEquals(
                listOf(setOf(BookCachePreference.Type), setOf(BookCachePreference.Replace)),
                repo.fields,
            )
            assertEquals("pdf", repo.exports.single().type)
        }

    @Test
    fun folderResultUsesStableDiskSelectionAcrossRoomReorderAndRestoresOnlyOnce() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.export("a")
            runCurrent()
            val ticket = vm.state.value.folder!!.ticket
            assertTrue(vm.consumeFolder(ticket))
            assertFalse(vm.consumeFolder(ticket))
            val restored = snapshot(saved)
            vm.stop()
            val next = model(repo, restored)
            runCurrent()
            assertTrue(next.state.value.folder!!.delivered)
            assertFalse(next.consumeFolder(ticket))
            repo.first.value = listOf(repo.book("changed"))
            runCurrent()
            next.folderResult("content://folder")
            runCurrent()
            assertEquals(listOf("a"), repo.exports.single().keys)
            assertEquals("content://folder", repo.path)
            assertNull(next.state.value.folder)
            assertTrue(repo.tickets.isEmpty())
        }

    @Test
    fun customScopeValidationSizeFallbackAndDraftRestoreDoNotExportUntilConfirmed() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.prefs = repo.prefs.copy(type = 1, custom = true, episodeFileName = "name")
            repo.path = "path"
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.export("a")
            runCurrent()
            assertNotNull(vm.state.value.section)
            vm.section(size = "not a number", scope = "bad", name = "author")
            vm.confirmSection()
            runCurrent()
            assertTrue(vm.state.value.section!!.invalidScope)
            assertTrue(repo.exports.isEmpty())
            vm.section(scope = "1-5,8,10-18")
            val restored = snapshot(saved)
            vm.stop()
            val next = model(repo, restored)
            runCurrent()
            assertEquals("author", next.state.value.section!!.name)
            assertEquals("1-5,8,10-18", next.state.value.section!!.scope)
            assertTrue(repo.exports.isEmpty())
            next.confirmSection()
            runCurrent()
            val request = repo.exports.single()
            assertEquals(1, request.size)
            assertEquals("epub", request.type)
            assertEquals("1-5,8,10-18", request.scope)
            assertNull(next.state.value.section)
        }

    @Test
    fun exportAllBySavedPathSkipsCustomAndFolderOnlyDoesNotExportBooks() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.path = "path"
            repo.prefs = repo.prefs.copy(type = 1, custom = true)
            val vm = model(repo)
            runCurrent()
            vm.export()
            runCurrent()
            assertNull(vm.state.value.section)
            assertEquals(listOf("a"), repo.exports.single().keys)
            vm.export(folderOnly = true)
            runCurrent()
            vm.folderResult("new")
            runCurrent()
            assertNotNull(vm.state.value.section)
            vm.section(all = true)
            vm.confirmSection()
            runCurrent()
            assertEquals(emptyList<String>(), repo.exports.last().keys)
            assertEquals("new", repo.path)
        }

    @Test
    fun canceledStageReleasesOwnedTicketAndLatePreviewCannotOverwriteChangedInput() =
        runTest(dispatcher) {
            val staged = Fake()
            staged.stageGate = CompletableDeferred()
            val stopped = model(staged)
            runCurrent()
            stopped.export("a")
            runCurrent()
            stopped.stop()
            staged.stageGate!!.complete(Unit)
            runCurrent()
            assertTrue(staged.tickets.isEmpty())
            assertTrue(staged.exports.isEmpty())
            val repo = Fake()
            repo.path = "path"
            repo.prefs = repo.prefs.copy(custom = true, type = 1)
            repo.previewGate = CompletableDeferred()
            val vm = model(repo)
            runCurrent()
            vm.export("a")
            runCurrent()
            vm.previewEpisodeName()
            runCurrent()
            vm.section(name = "changed")
            repo.previewGate!!.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.section!!.preview)
            assertEquals("changed", vm.state.value.section!!.name)
            vm.cancelSection()
            runCurrent()
            assertTrue(repo.tickets.isEmpty())
            assertTrue(repo.exports.isEmpty())
        }

    @Test
    fun failedExportKeepsTicketForExplicitRetryWithoutRepeatedAutomaticDispatch() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.path = "path"
            repo.failExport = true
            val vm = model(repo)
            runCurrent()
            vm.export("a")
            runCurrent()
            assertNotNull(vm.state.value.error)
            assertEquals(1, repo.tickets.size)
            vm.refresh()
            runCurrent()
            assertEquals(1, repo.exportAttempts)
            repo.failExport = false
            vm.retryExport()
            runCurrent()
            assertEquals(2, repo.exportAttempts)
            assertEquals(1, repo.exports.size)
            assertTrue(repo.tickets.isEmpty())
        }

    @Test
    fun hugeCustomDraftRestoresFromDiskTicketAndSavedStateContainsNoDraftBody() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.path = "path"
            repo.prefs = repo.prefs.copy(type = 1, custom = true)
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.export("a")
            runCurrent()
            val name = "name + '" + "n".repeat(300_000) + "'"
            val scope = "1,".repeat(200_000) + "2"
            vm.section(name = name, scope = scope)
            vm.flushSection()
            runCurrent()
            assertTrue(
                saved.keys().mapNotNull { saved.get<Any?>(it) as? String }.all { it.length < 256 }
            )
            val nextState = snapshot(saved)
            vm.stop()
            val next = model(repo, nextState)
            runCurrent()
            assertEquals(name, next.state.value.section!!.name)
            assertEquals(scope, next.state.value.section!!.scope)
            assertTrue(repo.exports.isEmpty())
        }

    @Test
    fun failedEarlierPreferenceDeltaBlocksExportAfterLaterSuccessfulDeltaUntilExplicitSync() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.path = "path"
            repo.failType = true
            val vm = model(repo)
            runCurrent()
            vm.preferences { it.copy(type = 2) }
            runCurrent()
            assertTrue(vm.state.value.preferencesDirty)
            vm.preferences { it.copy(parallel = true) }
            runCurrent()
            assertTrue(vm.state.value.preferencesDirty)
            vm.export("a")
            runCurrent()
            assertTrue(repo.exports.isEmpty())
            assertTrue(repo.tickets.isEmpty())
            repo.failType = false
            vm.retryPreferences()
            runCurrent()
            assertFalse(vm.state.value.preferencesDirty)
            vm.export("a")
            runCurrent()
            assertEquals("pdf", repo.exports.single().type)
            assertEquals(setOf(BookCachePreference.Type), repo.fields.last())
        }

    @Test
    fun menuRefreshCannotOverwriteNewPreferenceInputAfterNonCooperativeRead() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            repo.preferenceReadGate = CompletableDeferred()
            vm.refreshPreferences()
            runCurrent()
            vm.preferences { it.copy(type = 2) }
            runCurrent()
            repo.preferenceReadGate!!.complete(Unit)
            runCurrent()
            assertEquals(2, vm.state.value.preferences.type)
        }

    @Test
    fun explicitCloseCleansUnusedFolderTicketAndFinishedRestoreCannotDeliverAgain() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.export("a")
            runCurrent()
            assertEquals(1, repo.tickets.size)
            vm.close()
            runCurrent()
            assertTrue(repo.tickets.isEmpty())
            assertTrue(vm.state.value.closed)
            val next = model(repo, snapshot(saved))
            runCurrent()
            assertTrue(next.state.value.closed)
            assertNull(next.state.value.folder)
            assertTrue(repo.exports.isEmpty())
        }

    @Test
    fun closeDuringNonCooperativeDispatchRetainsTicketUntilServiceReceiptThenReleasesWithoutUiEvent() =
        runTest(dispatcher) {
            val repo = Fake()
            repo.path = "path"
            repo.exportGate = CompletableDeferred()
            val vm = model(repo)
            runCurrent()
            vm.export("a")
            runCurrent()
            assertEquals(1, repo.tickets.size)
            vm.close()
            val closed = vm.state.value
            runCurrent()
            assertEquals(1, repo.tickets.size)
            repo.exportGate!!.complete(Unit)
            runCurrent()
            assertTrue(repo.tickets.isEmpty())
            assertEquals(1, repo.exports.size)
            assertEquals(closed, vm.state.value)
        }

    @Test
    fun nativeFolderResultBeforeLoadingIsDurableAndRestoresOnceWithoutRawResultInSavedState() =
        runTest(dispatcher) {
            val repo = Fake()
            val ticket = repo.stage(listOf("a"))
            val saved =
                SavedStateHandle(
                    mapOf(
                        "cache.ticket" to ticket,
                        "cache.folder" to true,
                        "cache.folderDelivered" to true,
                    )
                )
            repo.preferenceReadGate = CompletableDeferred()
            val vm = model(repo, saved)
            runCurrent()
            vm.folderResult(ticket, "content://selected-folder")
            runCurrent()
            assertEquals(BookCacheFolderResult("content://selected-folder"), repo.results[ticket])
            assertTrue(repo.exports.isEmpty())
            val restored = snapshot(saved)
            vm.stop()
            val next = model(repo, restored)
            repo.preferenceReadGate!!.complete(Unit)
            runCurrent()
            assertEquals(1, repo.exports.size)
            assertEquals("content://selected-folder", repo.exports.single().path)
            next.folderResult(ticket, "late")
            runCurrent()
            assertEquals(1, repo.exports.size)
            assertFalse(
                restored
                    .keys()
                    .mapNotNull { restored.get<Any?>(it) as? String }
                    .contains("content://selected-folder")
            )
        }

    @Test
    fun canceledTicketCannotOverwriteNewRequestAndDuplicateNativeResultsUseFirstReceiptOnly() =
        runTest(dispatcher) {
            val repo = Fake()
            val vm = model(repo)
            runCurrent()
            vm.export("a")
            runCurrent()
            val old = vm.state.value.folder!!.ticket
            vm.folderResult(old, null)
            runCurrent()
            assertTrue(repo.tickets.isEmpty())
            vm.export("a")
            runCurrent()
            val current = vm.state.value.folder!!.ticket
            assertNotEquals(old, current)
            vm.folderResult(old, "late-old")
            runCurrent()
            assertEquals(current, vm.state.value.folder!!.ticket)
            assertTrue(repo.exports.isEmpty())
            vm.folderResult(current, "first")
            vm.folderResult(current, "duplicate")
            runCurrent()
            assertEquals(1, repo.exports.size)
            assertEquals("first", repo.exports.single().path)
            assertTrue(repo.tickets.isEmpty())
        }

    @Test
    fun generalFilenameDraftUsesDiskAndRestoresLargeScriptWithoutSavingItInBundleOrBeforeSave() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val vm = model(repo, saved)
            runCurrent()
            vm.openSettings("name")
            runCurrent()
            val script = "name + '" + "x".repeat(400_000) + "'"
            vm.settings(script)
            vm.flushSection()
            runCurrent()
            assertNull(repo.prefs.fileName)
            assertTrue(
                saved.keys().mapNotNull { saved.get<Any?>(it) as? String }.all { it.length < 256 }
            )
            val restored = snapshot(saved)
            vm.stop()
            val next = model(repo, restored)
            runCurrent()
            assertEquals(script, next.state.value.settings!!.draft)
            next.cancelSettings()
            runCurrent()
            assertNull(repo.prefs.fileName)
            assertTrue(repo.tickets.isEmpty())
            next.openSettings("charset")
            runCurrent()
            next.settings("GBK")
            next.confirmSettings()
            runCurrent()
            assertEquals("GBK", repo.prefs.charset)
            assertNull(next.state.value.settings)
            assertTrue(repo.tickets.isEmpty())
        }

    private class Fake : BookCacheRepository {
        fun book(key: String) = BookCacheItem(key, key, "author", false, 10, 4, 9)

        val first = MutableStateFlow(listOf(book("a")))
        val second = MutableStateFlow(listOf(book("b")))
        var scanGate: CompletableDeferred<Unit>? = null
        var previewGate: CompletableDeferred<Unit>? = null
        var stageGate: CompletableDeferred<Unit>? = null
        var preferenceGate: CompletableDeferred<Unit>? = null
        val fields = mutableListOf<Set<BookCachePreference>>()
        var preferenceReadGate: CompletableDeferred<Unit>? = null
        var exportGate: CompletableDeferred<Unit>? = null
        var failType = false
        var prefs = BookCachePreferences()
        var path: String? = null
        var runtimeValue = BookCacheRuntime(false, emptySet(), emptyMap(), emptyMap())
        val writes = mutableListOf<BookCachePreferences>()
        val downloads = mutableListOf<Pair<List<String>, Boolean>>()
        val toggles = mutableListOf<String>()
        var stops = 0
        val results = mutableMapOf<String, BookCacheFolderResult>()
        val sections = mutableMapOf<String, BookCacheSectionDraft>()
        var nextTicket = 0
        val tickets = mutableMapOf<String, List<String>>()
        val exports = mutableListOf<BookCacheExport>()
        var failExport = false
        var exportAttempts = 0

        override fun books(group: Long): Flow<List<BookCacheItem>> =
            if (group == 2L) second else first

        override fun groups() = flowOf(listOf(BookCacheGroup(2, "other")))

        override suspend fun scan(key: String): BookCacheScan {
            withContext(NonCancellable) { scanGate?.await() }
            return BookCacheScan(setOf("old"), 8)
        }

        override suspend fun runtime() = runtimeValue

        override suspend fun preferences(): BookCachePreferences {
            val value = prefs
            withContext(NonCancellable) { preferenceReadGate?.await() }
            return value
        }

        override suspend fun preferences(
            value: BookCachePreferences,
            fields: Set<BookCachePreference>,
        ) {
            withContext(NonCancellable) { preferenceGate?.await() }
            this.fields += fields
            if (failType && BookCachePreference.Type in fields) error("failed type")
            writes += value
            prefs = value
        }

        override suspend fun cachedPath() = path

        override suspend fun rememberPath(path: String) {
            this.path = path
        }

        override suspend fun writable(path: String) = true

        override suspend fun download(keys: List<String>, afterCurrent: Boolean) {
            downloads += keys to afterCurrent
        }

        override suspend fun stopDownloads() {
            stops++
        }

        override suspend fun toggleDownload(key: String) {
            toggles += key
        }

        override suspend fun export(request: BookCacheExport) {
            exportAttempts++
            withContext(NonCancellable) { exportGate?.await() }
            if (failExport) error("disk")
            exports += request
        }

        override suspend fun stage(keys: List<String>): String {
            withContext(NonCancellable) { stageGate?.await() }
            val id = "ticket-${nextTicket++}"
            tickets[id] = keys.toList()
            return id
        }

        override suspend fun staged(ticket: String) = tickets.getValue(ticket)

        override suspend fun readSection(ticket: String) = sections[ticket]

        override suspend fun writeSection(ticket: String, draft: BookCacheSectionDraft) {
            if ((sections[ticket]?.revision ?: -1) < draft.revision) sections[ticket] = draft
        }

        override suspend fun folderResult(ticket: String) = results[ticket]

        override suspend fun folderResult(ticket: String, result: BookCacheFolderResult): Boolean {
            if (ticket !in tickets || ticket in results) return false
            results[ticket] = result
            return true
        }

        override suspend fun release(ticket: String) {
            tickets.remove(ticket)
            sections.remove(ticket)
            results.remove(ticket)
        }

        override suspend fun validEpisodeName(script: String) = script.isNotEmpty()

        override suspend fun episodeName(key: String, script: String): String {
            withContext(NonCancellable) { previewGate?.await() }
            return "$script.epub"
        }
    }
}
