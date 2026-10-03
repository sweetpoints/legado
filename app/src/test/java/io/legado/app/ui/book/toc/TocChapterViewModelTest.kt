package io.legado.app.ui.book.toc

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
import io.legado.app.model.AudioCacheKey
import io.legado.app.model.AudioCacheStateChanged
import io.legado.app.model.book.toc.*
import io.legado.app.model.localBook.*
import io.legado.app.help.book.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class TocChapterViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<TocChapterViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val book = Book(bookUrl = "book", origin = "remote", name = "Book", totalChapterNum = 6, durChapterIndex = 2, durChapterTitle = "Current")
    private class Fake : TocChapterRepository {
        var snapshot = TocChapterSnapshot(listOf(BookChapter(url = "volume", index = 0, title = "Volume", isVolume = true),
            BookChapter(url = "one", index = 1, title = "One"), BookChapter(url = "two", index = 2, title = "Two"),
            BookChapter(url = "other", index = 3, title = "Other", isVolume = true), BookChapter(url = "four", index = 4, title = "Four")), null, emptyList())
        val checkpoints = mutableMapOf<String, TocChapterCheckpoint>()
        var loadGate: CompletableDeferred<Unit>? = null; var cacheGate: CompletableDeferred<Unit>? = null; var readGate: CompletableDeferred<Unit>? = null
        var titleGate: CompletableDeferred<Unit>? = null; var suffix = ""; var searches = mutableListOf<String>(); var cache = TocChapterCache()
        override suspend fun checkpoint(session: String) = checkpoints[session]
        override suspend fun checkpoint(session: String, value: TocChapterCheckpoint) { if ((checkpoints[session]?.revision ?: -1) <= value.revision) checkpoints[session] = value }
        override suspend fun release(session: String) { checkpoints.remove(session) }
        override suspend fun load(parameters: TocChapterParameters): TocChapterSnapshot { val value = snapshot; withContext(NonCancellable) { loadGate?.await() }; return value }
        override suspend fun search(parameters: TocChapterParameters, query: String): List<Int> { searches += query; return snapshot.chapters.filter { it.title.contains(query, true) }.map { it.index } }
        override fun titles(book: Book, items: List<TocListItem>) = flow { val value = suffix; withContext(NonCancellable) { titleGate?.await() }; items.forEach { emit(it.key to (it.chapter.title + value)) } }
        override suspend fun title(book: Book, item: TocListItem) = item.chapter.title + suffix
        override suspend fun cache(book: Book): TocChapterCache { withContext(NonCancellable) { cacheGate?.await() }; return cache }
        override suspend fun resolve(book: Book, chapterUrl: String): TocChapterNavigation? { withContext(NonCancellable) { readGate?.await() }; return snapshot.chapters.find { it.url == chapterUrl }?.let { TocChapterNavigation(it.index, it.index != book.durChapterIndex) } }
    }
    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) = TocChapterViewModel(repo, saved).also { models += it }
    private fun copy(saved: SavedStateHandle) = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { try { block() } finally { models.forEach { it.stop() }; runCurrent() } }
    @Test fun initialCurrentHeaderAndLocationRetainOriginalIndexAndTopBottomNeverReuseTokens() = test {
        val model = model(Fake()); model.bind(TocChapterParameters(book)); runCurrent()
        assertEquals("Current(3/6)", model.state.value.currentInfo); assertEquals(2, model.state.value.scrollTarget)
        model.top(); val first = model.state.value.scrollRequest; model.scrolled(first); model.bottom(); val second = model.state.value.scrollRequest
        assertTrue(second > first); assertEquals(4, model.state.value.scrollTarget); model.scrolled(first); assertEquals(second, model.state.value.scrollRequest)
    }
    @Test fun typingWhileDocumentIsLoadingUsesLatestSearchWithoutCancelingParseAndDebouncesFurtherQueries() = test {
        val repo = Fake(); repo.loadGate = CompletableDeferred(); val model = model(repo); model.bind(TocChapterParameters(book)); runCurrent()
        model.search("One"); model.search("Four"); repo.loadGate!!.complete(Unit); runCurrent()
        assertEquals("Four", model.state.value.query); assertEquals(listOf("volume:3", "chapter:4"), model.state.value.rows.map { it.key })
        model.search("One"); advanceTimeBy(80); model.search("Two"); advanceTimeBy(149); runCurrent(); assertFalse(repo.searches.contains("One"))
        advanceTimeBy(1); runCurrent(); assertEquals("Two", repo.searches.last())
    }
    @Test fun volumeToggleKeepsVisibleAnchorAndLocateExpandsCurrentPath() = test {
        val model = model(Fake()); model.bind(TocChapterParameters(book)); runCurrent()
        model.toggle("volume:0", "chapter:1"); runCurrent(); assertEquals(0, model.state.value.scrollTarget)
        assertFalse(model.state.value.rows.any { it.key == "chapter:2" }); model.locate(); runCurrent()
        assertTrue(model.state.value.rows.any { it.key == "chapter:2" }); assertEquals(2, model.state.value.scrollTarget)
        model.search("Two"); advanceTimeBy(150); runCurrent(); model.toggle("volume:0"); assertFalse(model.state.value.rows.first().canToggle)
    }
    @Test fun reverseDisplayUsesCurrentHostBookAndExplicitResetRestoresExpandedPreference() = test {
        val repo = Fake(); val model = model(repo); model.bind(TocChapterParameters(book)); runCurrent(); model.toggle("volume:0"); runCurrent()
        val reversed = book.copy().apply { setReverseTocDisplay(true) }; model.update(TocChapterParameters(reversed), resetCollapse = true, replaceAll = true); runCurrent()
        assertEquals(listOf("volume:3", "chapter:4", "volume:0", "chapter:2", "chapter:1"), model.state.value.rows.map { it.key })
        assertTrue(model.state.value.rows.filter { it.volume }.all { !it.collapsed })
    }
    @Test fun fullQueryAndCollapsedVolumesRestoreFromDiskWithSmallSavedStateOnly() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val first = model(repo, saved); first.bind(TocChapterParameters(book)); runCurrent()
        first.toggle("volume:0"); runCurrent(); first.stop(); val restored = model(repo, copy(saved)); runCurrent()
        assertFalse(restored.state.value.rows.any { it.key == "chapter:1" })
        restored.search("Query".repeat(100000)); advanceTimeBy(150); runCurrent(); val restoredSaved = copy(saved)
        assertTrue(restoredSaved.keys().all { (restoredSaved.get<Any?>(it) as? String)?.length?.let { size -> size < 2000 } != false })
        assertEquals(500000, repo.checkpoints.values.single().parameters.search!!.length)
    }
    @Test fun audioEventsDuringCacheEnumerationOverrideSnapshotAndRejectForeignTreesAndBooks() = test {
        val repo = Fake(); repo.cacheGate = CompletableDeferred(); repo.cache = TocChapterCache(tree = "tree"); val audio = book.copy(type = BookType.audio)
        val model = model(repo); model.bind(TocChapterParameters(audio)); runCurrent(); val key = AudioCacheKey.from(repo.snapshot.chapters[1])
        assertTrue(model.state.value.rows.single { it.key == "chapter:1" }.cached)
        model.audioChanged(AudioCacheStateChanged("foreign", key, true, "tree"), "tree")
        model.audioChanged(AudioCacheStateChanged("book", key, true, "wrong"), "tree")
        model.audioChanged(AudioCacheStateChanged("book", key, true, "tree"), "tree"); repo.cacheGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.rows.single { it.key == "chapter:1" }.cached)
        assertFalse(model.state.value.rows.single { it.key == "chapter:2" }.cached)
        model.audioChanged(AudioCacheStateChanged("book", key, false, "tree"), "tree"); assertFalse(model.state.value.rows.single { it.key == "chapter:1" }.cached)
    }
    @Test fun savedTextEventDuringLoadIsNotLostAndWordCountSettingUpdatesExistingRows() = test {
        val repo = Fake(); repo.cacheGate = CompletableDeferred(); repo.snapshot = repo.snapshot.copy(chapters = repo.snapshot.chapters.map { it.copy(wordCount = "10") })
        val model = model(repo); model.bind(TocChapterParameters(book)); runCurrent(); model.contentSaved("book", repo.snapshot.chapters[1]); repo.cacheGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.rows.single { it.key == "chapter:1" }.cached); assertNull(model.state.value.rows[1].words)
        model.countWords(true); assertEquals("10", model.state.value.rows[1].words)
    }
    @Test fun canceledNonCooperativeOldTitleAndDocumentLoadsCannotOverwriteNewBook() = test {
        val repo = Fake(); repo.loadGate = CompletableDeferred(); val model = model(repo); model.bind(TocChapterParameters(book)); runCurrent()
        model.bind(TocChapterParameters(book.copy(bookUrl = "new"))); repo.loadGate!!.complete(Unit); runCurrent()
        assertEquals("new", repo.checkpoints.values.single().parameters.book.bookUrl)
        repo.titleGate = CompletableDeferred(); repo.suffix = "stale"; model.clearTitles(); runCurrent(); repo.suffix = "fresh"; model.clearTitles(); runCurrent()
        repo.titleGate!!.complete(Unit); runCurrent(); assertTrue(model.state.value.rows.all { it.title.endsWith("fresh") })
    }
    @Test fun pausedNonCooperativeReadKeepsSmallTicketAndReReadsLatestChapterBeforeAcknowledgingOnce() = test {
        val repo = Fake(); val saved = SavedStateHandle(); val model = model(repo, saved); model.bind(TocChapterParameters(book)); runCurrent(); model.request("chapter:1")
        val ticket = model.state.value.open!!; repo.readGate = CompletableDeferred(); var delivered = false
        val read = launch { model.resolve(ticket); delivered = true }; runCurrent(); read.cancel(); repo.readGate!!.complete(Unit); runCurrent()
        assertFalse(delivered); assertEquals(ticket, model.state.value.open); repo.snapshot = repo.snapshot.copy(chapters = repo.snapshot.chapters.map { if (it.url == "one") it.copy(index = 5) else it })
        assertEquals(5, model.resolve(ticket)!!.navigation!!.index); assertNull(model.delivered("wrong")); assertEquals(ticket, model.delivered(ticket.nonce)); assertNull(model.delivered(ticket.nonce))
    }
    @Test fun pdfOutlineSearchExpandsAncestorsAndNavigationKeepsRawPageAndTenPageSegments() = test {
        val repo = Fake(); repo.snapshot = repo.snapshot.copy(pdf = listOf(PdfOutlineNode(0, null, 0, "Parent", null), PdfOutlineNode(1, 0, 1, "Page", 27)))
        val model = model(repo); model.bind(TocChapterParameters(book)); runCurrent(); model.toggle("pdf:0"); assertEquals(1, model.state.value.rows.size)
        model.search("Page"); runCurrent(); assertEquals(2, model.state.value.rows.size); model.request("pdf:1"); val navigation = model.resolve(model.state.value.open!!)!!.navigation!!
        assertEquals(2, navigation.index); assertEquals(27, navigation.pdfPage); assertFalse(navigation.changed)
    }
    @Test fun epubNavigationOnlyParentTogglesWhileReferencedContentRetainsReadingIdentityAndFullTitleToast() = test {
        val repo = Fake(); repo.snapshot = repo.snapshot.copy(epub = listOf(EpubTocNode(0, null, 0, "Navigation", null), EpubTocNode(1, 0, 1, "Entry", "one")))
        val model = model(repo); model.bind(TocChapterParameters(book.copy(durChapterIndex = 1))); runCurrent(); model.request("volume:-1"); runCurrent()
        assertNull(model.state.value.open); model.request("volume:-1", true); val ticket = model.state.value.open!!
        assertEquals("Navigation", model.resolve(ticket)!!.title); model.delivered(ticket.nonce); model.locate(); runCurrent()
        assertTrue(model.state.value.rows.any { it.readingIndex == 1 })
    }
    @Test fun externalHostReadConfigMutationCannotChangeOwnedCheckpointOrRowsBeforeExplicitUpdate() = test {
        val repo = Fake(); val host = book.copy().apply { setReverseTocDisplay(false) }
        val model = model(repo); model.bind(TocChapterParameters(host)); runCurrent()
        host.setReverseTocDisplay(true)
        assertFalse(repo.checkpoints.values.single().parameters.book.getReverseTocDisplay())
        model.update(TocChapterParameters(host)); runCurrent()
        assertEquals("volume:3", model.state.value.rows.first().key)
    }

    @Test fun queuedAudioEventFromOldTreeCannotOverrideCacheSnapshotAfterTreeChangesDuringEnumeration() = test {
        val repo = Fake(); repo.cacheGate = CompletableDeferred(); repo.cache = TocChapterCache(tree = "new")
        val model = model(repo); model.bind(TocChapterParameters(book.copy(type = BookType.audio))); runCurrent()
        val key = AudioCacheKey.from(repo.snapshot.chapters[1])
        model.audioChanged(AudioCacheStateChanged("book", key, true, "old"), "old")
        repo.cacheGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.rows.single { it.key == "chapter:1" }.cached)
    }

}
