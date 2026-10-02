package io.legado.app.ui.book.changesource

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.legado.app.data.entities.*
import io.legado.app.data.preferences.*
import io.legado.app.data.repository.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterSourceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun chapter(index: Int, title: String, volume: Boolean = false) = BookChapter(bookUrl = "old", url = "chapter-$index-$title", index = index, title = title, isVolume = volume).let {
        ChapterSourceChapter("$index-$title", index, title, volume, "Tag", GSON.toJson(it))
    }
    private val originals get() = listOf(chapter(0, "Volume", true), chapter(1, "第一章 开始"), chapter(2, "第二章 继续"))
    private fun row(id: String, origin: String = id): ChapterSourceSearchRow {
        val book = SearchBook(bookUrl = id, origin = origin, name = "Book", author = "Author", type = 0)
        return ChapterSourceSearchRow(id, origin, origin, "Book", "Author", "Latest", null, -1, -1, 0, 0, 0, GSON.toJson(book))
    }
    private fun snapshot(batch: Boolean = true) = ChapterSourceSession(ChapterSourceSearchRequest("Book", "Author",
        originalBookJson = GSON.toJson(Book(bookUrl = "old", origin = "old-source", originName = "Old", name = "Book", author = "Author", type = 0)), currentBookUrl = "old"), 1, "第一章 开始", batch, originalChapters = originals)
    private fun owned(vm: ChapterSourceViewModel) = ViewModelStore().apply { put("chapter", vm) }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private suspend fun ready(vm: ChapterSourceViewModel) { vm.state.first { !it.loading && it.request != null }; yield() }
    private fun vm(store: FakeContent, search: FakeSearch = FakeSearch(), preferences: FakeSettings = FakeSettings(), saved: SavedStateHandle = SavedStateHandle(), seed: ChapterSourceSession = snapshot()) =
        ChapterSourceViewModel(search, store, preferences, saved) { seed }
    @Test fun selectionAndQueryRestoreFromPrivateSessionWithoutLargePayloadInSavedState() = runTest(dispatcher) {
        val content = FakeContent(); val search = FakeSearch(); val saved = SavedStateHandle(); val model = vm(content, search, saved = saved); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { !it.tocLoading && it.toc != null }
            model.chapter(0); model.chapter(1); model.query("Source"); runCurrent()
            assertEquals(setOf(1, 2), model.state.value.selected)
            assertTrue(saved.keys().all { saved.get<Any?>(it).toString().length < 100 })
            val restored = vm(content, search, saved = copy(saved)); val other = owned(restored)
            try { ready(restored); assertEquals(setOf(1, 2), restored.state.value.selected); assertEquals("Source", restored.state.value.request!!.query)
                assertTrue(restored.state.value.tocVisible); assertTrue(content.cacheCalls.isEmpty())
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun manualCacheAdvancesOnlyAfterConsumptionAndDuplicateConsumptionCannotAdvanceAgain() = runTest(dispatcher) {
        val content = FakeContent(); val model = vm(content); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }
            model.chapter(0); model.chapter(1); model.cacheSelected(); val pending = model.state.first { it.pendingReceipt != null }
            assertEquals(listOf(listOf(0, 1)), content.cacheCalls); assertEquals(1, pending.chapterIndex)
            val receipt = model.prepareReceipt(pending.pendingReceipt!!); model.consumeReceipt(receipt); model.consumeReceipt(receipt); runCurrent()
            assertEquals(2, model.state.value.chapterIndex); assertTrue(model.state.value.selected.isEmpty()); assertNull(model.state.value.pendingReceipt)
            model.skip(); assertTrue(model.state.value.finished)
        } finally { owner.clear() }
    }
    @Test fun automationUniqueMatchCachingAndStopAfterCurrentPreserveNextOriginalPosition() = runTest(dispatcher) {
        val content = FakeContent().apply { commitGate = CompletableDeferred() }; val model = vm(content); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }
            assertEquals(1..2, model.rangeDefaults()); assertFalse(model.startAutomation(0, 2)); assertTrue(model.startAutomation(1, 2))
            model.runAutomationIfReady(); model.state.first { it.caching }; content.commitStarted.await()
            model.stopAutomation(); assertTrue(model.state.value.automation!!.stopAfterCurrent)
            content.commitGate!!.complete(Unit); val pending = model.state.first { it.pendingReceipt != null }
            model.consumeReceipt(model.prepareReceipt(pending.pendingReceipt!!)); runCurrent()
            assertEquals(2, model.state.value.chapterIndex); assertNull(model.state.value.automation); assertFalse(model.state.value.finished)
            assertEquals(listOf(listOf(0)), content.cacheCalls)
        } finally { content.commitGate?.complete(Unit); owner.clear() }
    }
    @Test fun ambiguousAndMissingMatchesPauseAndManualSelectionOrSkipContinuesRange() = runTest(dispatcher) {
        val content = FakeContent().apply { target = target.copy(chapters = listOf(chapter(1, "第一章 开始"), chapter(3, "第一章 开始"))) }
        val model = vm(content); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }
            assertTrue(model.startAutomation(1, 2)); model.runAutomationIfReady(); model.state.first { it.automation?.stage == "Paused" }
            assertEquals("Ambiguous", model.state.value.automation!!.reason); assertTrue(content.cacheCalls.isEmpty()); assertTrue(model.state.value.selected.isEmpty())
            model.chapter(0); model.chapter(1); model.cacheSelected(); val pending = model.state.first { it.pendingReceipt != null }
            model.consumeReceipt(model.prepareReceipt(pending.pendingReceipt!!)); runCurrent(); assertEquals(2, model.state.value.chapterIndex)
            model.runAutomationIfReady(); model.state.first { it.automation?.stage == "Paused" }
            assertEquals("Missing", model.state.value.automation!!.reason); model.skip(); assertTrue(model.state.value.finished)
        } finally { owner.clear() }
    }
    @Test fun failedAutomationCacheKeepsSelectionAndSupportsExplicitRetry() = runTest(dispatcher) {
        val content = FakeContent().apply { cacheFails = true }; val model = vm(content); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }
            model.startAutomation(1, 2); model.runAutomationIfReady(); model.state.first { it.error != null && !it.busy }
            assertEquals("Paused", model.state.value.automation!!.stage); assertEquals(setOf(1), model.state.value.selected); assertNull(model.state.value.pendingReceipt)
            content.cacheFails = false; model.cacheSelected(); model.state.first { it.pendingReceipt != null }
            assertEquals(2, content.cacheCalls.size)
        } finally { owner.clear() }
    }
    @Test fun processRestorePausesActiveRangeAndNeverAutomaticallyRepeatsCacheCommit() = runTest(dispatcher) {
        val content = FakeContent(); val saved = SavedStateHandle(); val model = vm(content, saved = saved); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }; model.startAutomation(1, 2); runCurrent()
            val restored = vm(content, saved = copy(saved)); val other = owned(restored)
            try { ready(restored); assertEquals("Paused", restored.state.value.automation!!.stage); assertEquals(1, restored.state.value.chapterIndex); assertTrue(content.cacheCalls.isEmpty()) }
            finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun pendingReceiptRestoresButConsumedReceiptAdvancesOnlyOnceWithoutHostReplay() = runTest(dispatcher) {
        val content = FakeContent(); val saved = SavedStateHandle(); val model = vm(content, saved = saved); val owner = owned(model)
        try { ready(model); model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }; model.chapter(0); model.cacheSelected()
            val pending = model.state.first { it.pendingReceipt != null }; val restored = vm(content, saved = copy(saved)); val other = owned(restored)
            try { ready(restored); assertEquals(pending.pendingReceipt, restored.state.value.pendingReceipt)
                restored.consumeReceipt(restored.prepareReceipt(pending.pendingReceipt!!)); runCurrent()
                val next = vm(content, saved = copy(saved)); val last = owned(next)
                try { ready(next); assertNull(next.state.value.pendingReceipt); assertEquals(2, next.state.value.chapterIndex); assertEquals(1, content.cacheCalls.size) }
                finally { last.clear() }
            } finally { other.clear() }
        } finally { owner.clear() }
    }
    @Test fun sourceDeletionWaitsForReplacementAcknowledgementAndDuplicateCallbackDeletesOnce() = runTest(dispatcher) {
        val content = FakeContent(); val search = FakeSearch().apply { rows = listOf(row("old", "old-source"), row("target")) }
        val model = vm(content, search); val owner = owned(model)
        try { ready(model); model.deleteSource("old"); val pending = model.state.first { it.pendingReceipt != null }
            assertTrue(content.deleted.isEmpty()); val receipt = model.prepareReceipt(pending.pendingReceipt!!); assertEquals("old", receipt.deleteAfterId)
            model.consumeReceipt(receipt); assertTrue(content.deleted.isEmpty()); model.completeSourceChange(receipt); model.completeSourceChange(receipt); runCurrent()
            assertEquals(listOf("old"), content.deleted)
        } finally { owner.clear() }
    }
    @Test fun failedReplacementDoesNotDeleteCurrentSourceAndTocCancellationRejectsLateBodyResult() = runTest(dispatcher) {
        val content = FakeContent().apply { contentGate = CompletableDeferred() }; val search = FakeSearch().apply { rows = listOf(row("old", "old-source")) }
        val model = vm(content, search, seed = snapshot(false)); val owner = owned(model)
        try { ready(model); model.deleteSource("old"); model.state.first { it.error != null && !it.busy }
            assertEquals("没有有效源", model.state.value.error); assertTrue(content.deleted.isEmpty())
            search.rows = listOf(row("target")); model.refresh(); model.state.first { it.rows.any { it.id == "target" } }
            model.openToc("target"); model.state.first { it.toc != null && !it.tocLoading }; model.chapter(0); runCurrent(); assertTrue(model.state.value.contentLoading)
            model.hideToc(); content.contentGate!!.complete(Unit); runCurrent()
            assertNull(model.state.value.pendingReceipt); assertFalse(model.state.value.contentLoading); assertFalse(model.state.value.tocVisible)
        } finally { content.contentGate?.complete(Unit); owner.clear() }
    }
    @Test fun failedSessionWriteBlocksHostOperationsUntilExplicitRetryPreservesDraft() = runTest(dispatcher) {
        val content = FakeContent(); val model = vm(content); val owner = owned(model)
        try { ready(model); content.writeFails = true; model.query("Draft query"); runCurrent()
            assertTrue(model.state.value.persistError); assertEquals("Draft query", model.state.value.request!!.query)
            model.deleteSource("target"); runCurrent(); assertTrue(content.deleted.isEmpty())
            content.writeFails = false; model.retry(); runCurrent()
            assertFalse(model.state.value.persistError); assertEquals("Draft query", content.stored!!.request.query)
        } finally { owner.clear() }
    }
    @Test fun conflictedRecoveryRequiresExplicitAbandonAndPreservesCommittedReceipt() = runTest(dispatcher) {
        val content = FakeContent().apply {
            stored = snapshot(); recoveryFails = true
            receipts["stale"] = ChapterSourceReceipt("stale", ChapterSourceReceiptKind.Cache, chapterIndex = 1)
            receipts["committed"] = ChapterSourceReceipt("committed", ChapterSourceReceiptKind.Cache, chapterIndex = 1, committed = true)
        }
        val model = vm(content); val owner = owned(model)
        try { model.state.first { !it.loading && it.cacheRecoveryError }
            assertNull(model.state.value.request); assertTrue(content.cacheCalls.isEmpty())
            model.retryCacheRecovery(); ready(model)
            assertTrue(content.receipts.getValue("stale").consumed); assertFalse(content.receipts.getValue("committed").consumed)
            assertEquals("committed", model.state.value.pendingReceipt); assertEquals(1, model.state.value.chapterIndex)
        } finally { owner.clear() }
    }
    @Test fun lateSearchProjectionUsesNewestQueryAndStoppedOwnerCannotPublishResults() = runTest(dispatcher) {
        val content = FakeContent(); val search = FakeSearch(); val model = vm(content, search); val owner = owned(model)
        try { ready(model)
            search.rows = listOf(row("target"), row("hidden").copy(name = "Other"))
            val projectionStarted = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            search.projectionStarted = projectionStarted; search.projectionGate = gate
            model.startSearch(); projectionStarted.await(); model.query("Other"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("hidden"), model.state.value.rows.map { it.id })
            assertEquals("Other", model.state.value.request!!.query)
            val lateGate = CompletableDeferred<Unit>(); val lateStarted = CompletableDeferred<Unit>()
            search.projectionGate = lateGate; search.projectionStarted = lateStarted
            model.startSearch(); lateStarted.await(); model.stop(); val stopped = model.state.value
            lateGate.complete(Unit); runCurrent(); assertEquals(stopped, model.state.value)
        } finally { owner.clear() }
    }
    @Test fun cancelledSearchWithNonCooperativeLateFailureCannotPublishAnError() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val search = FakeSearch(); val content = FakeContent(); val model = vm(content, search); val owner = owned(model)
        try { ready(model)
            search.stream = flow {
                started.complete(Unit); withContext(NonCancellable) { gate.await(); error("late source failure") }
            }
            model.startSearch(); started.await(); model.stopSearch(); val cancelled = model.state.value
            gate.complete(Unit); runCurrent(); assertEquals(cancelled, model.state.value); assertNull(model.state.value.error)
        } finally { gate.complete(Unit); owner.clear() }
    }
    private inner class FakeSearch : ChapterSourceSearchRepository {
        var rows = listOf(row("target")); var fail = false
        var stream: Flow<ChapterSourceSearchUpdate>? = null
        var projectionGate: CompletableDeferred<Unit>? = null; var projectionStarted: CompletableDeferred<Unit>? = null
        override suspend fun cached(request: ChapterSourceSearchRequest): ChapterSourceSearchUpdate {
            if (fail) error("search failed")
            val filtered = rows.filter { request.query.isEmpty() || it.name.contains(request.query) }
            return ChapterSourceSearchUpdate(filtered, false, allRows = filtered)
        }
        override fun search(request: ChapterSourceSearchRequest, previous: List<ChapterSourceSearchRow>) = stream ?: flowOf(ChapterSourceSearchUpdate(rows, false))
        override fun measure(request: ChapterSourceSearchRequest, previous: List<ChapterSourceSearchRow>, missingOnly: Boolean) = flowOf(ChapterSourceSearchUpdate(rows, false))
        override suspend fun project(request: ChapterSourceSearchRequest, rows: List<ChapterSourceSearchRow>): List<ChapterSourceSearchRow> {
            val gate = projectionGate; projectionGate = null
            if (gate != null) { projectionStarted?.complete(Unit); withContext(NonCancellable) { gate.await() } }
            return rows
        }
    }
    private class FakeSettings : ChapterSourceSettingsRepository {
        var value = ChapterSourceSettings("", true, false, false, false, false, 0, 0, 0)
        override suspend fun load() = value
        override suspend fun toggle(option: ChapterSourceOption): ChapterSourceSettings { value = when (option) {
            ChapterSourceOption.Author -> value.copy(author = !value.author)
            ChapterSourceOption.Info -> value.copy(info = !value.info)
            ChapterSourceOption.Toc -> value.copy(toc = !value.toc)
            ChapterSourceOption.WordCount -> value.copy(wordCount = !value.wordCount)
            ChapterSourceOption.ResponseTime -> value.copy(responseTime = !value.responseTime)
        }; return value }
        override suspend fun group(value: String): ChapterSourceSettings { this.value = this.value.copy(group = value); return this.value }
    }
    private inner class FakeContent : ChapterSourceContentRepository {
        var writeFails = false; var recoveryFails = false; var stored: ChapterSourceSession? = null; val receipts = linkedMapOf<String, ChapterSourceReceipt>(); val deleted = mutableListOf<String>()
        var target = ChapterSourceToc("target", GSON.toJson(Book(bookUrl = "target", origin = "target")), GSON.toJson(BookSource(bookSourceUrl = "target")), originals.filterNot { it.volume }, 0)
        val cacheCalls = mutableListOf<List<Int>>(); var cacheFails = false; var commitGate: CompletableDeferred<Unit>? = null
        val commitStarted = CompletableDeferred<Unit>(); var contentGate: CompletableDeferred<Unit>? = null
        override suspend fun original(bookJson: String) = originals
        override suspend fun toc(row: ChapterSourceSearchRow, index: Int, title: String) = target
        override suspend fun content(session: String, toc: ChapterSourceToc, position: Int): ChapterSourceReceipt {
            contentGate?.let { withContext(NonCancellable) { it.await() } }
            return ChapterSourceReceipt("content", ChapterSourceReceiptKind.Content, body = "Body", committed = true).also { receipts[it.key] = it }
        }
        override suspend fun cache(session: String, toc: ChapterSourceToc, positions: List<Int>, originalBookJson: String, originalChapter: ChapterSourceChapter, onCommit: suspend () -> Unit): ChapterSourceReceipt {
            cacheCalls += positions; if (cacheFails) error("cache failed")
            return withContext(NonCancellable) { onCommit(); commitStarted.complete(Unit); commitGate?.await()
                ChapterSourceReceipt("cache-${cacheCalls.size}", ChapterSourceReceiptKind.Cache, body = "Body", chapterIndex = originalChapter.index, targetPosition = positions.last() + 1, committed = true).also { receipts[it.key] = it } }
        }
        override suspend fun read(session: String) = stored
        override suspend fun write(session: String, snapshot: ChapterSourceSession) { if (writeFails) error("disk failed"); if ((stored?.revision ?: -1) <= snapshot.revision) stored = snapshot }
        override suspend fun receipt(session: String, key: String) = receipts.getValue(key)
        override suspend fun recoverCache(session: String, originalBookJson: String, originalChapters: List<ChapterSourceChapter>): ChapterSourceReceipt? { if (recoveryFails) error("缓存已被其它操作更新，请重新获取正文"); return receipts.values.lastOrNull { it.kind == ChapterSourceReceiptKind.Cache && !it.consumed } }
        override suspend fun consume(session: String, key: String) { receipts[key] = receipts.getValue(key).copy(consumed = true) }
        override suspend fun abandonUncommittedCache(session: String) { receipts.values.toList().filter { it.kind == ChapterSourceReceiptKind.Cache && !it.committed && !it.consumed }.forEach { receipts[it.key] = it.copy(consumed = true) }; recoveryFails = false }
        override suspend fun change(session: String, toc: ChapterSourceToc, deleteAfterId: String?) = ChapterSourceReceipt("change", ChapterSourceReceiptKind.Change, bookJson = toc.bookJson, sourceJson = toc.sourceJson, chapters = toc.chapters, deleteAfterId = deleteAfterId, committed = true).also { receipts[it.key] = it }
        override suspend fun deleteSource(row: ChapterSourceSearchRow) { deleted += row.id }
        override suspend fun disableSource(row: ChapterSourceSearchRow) = Unit
        override suspend fun order(row: ChapterSourceSearchRow, top: Boolean) = Unit
        override suspend fun score(row: ChapterSourceSearchRow, score: Int) = Unit
        override suspend fun groups() = listOf("Group")
    }
}
