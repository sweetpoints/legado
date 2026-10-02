package io.legado.app.ui.main.bookshelf.style2

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.BookType
import io.legado.app.data.entities.*
import io.legado.app.data.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfFolderViewModelTest {
    private val dispatcher = StandardTestDispatcher(); private val models = mutableListOf<BookshelfFolderViewModel>()
    private class Repository : BookshelfFolderRepository {
        val visible = MutableStateFlow(listOf(BookGroup(1, "One", bookSort = 3), BookGroup(2, "Two", enableRefresh = false, onlyUpdateRead = true)))
        val byGroup = mutableMapOf<Long, MutableSharedFlow<List<Book>>>()
        val preview = MutableStateFlow<List<BookshelfBook>>(emptyList())
        val options = MutableStateFlow(BookshelfPageSettings())
        val prefs = MutableStateFlow(BookshelfHomePreferences())
        val head = MutableStateFlow(BookshelfHomeHeader()); var fail = false
        override fun groups() = visible
        override fun books(groupId: Long): Flow<List<Book>> = if (fail) flow { error("failed") } else byGroup.getOrPut(groupId) { MutableSharedFlow(replay = 1) }
        override fun previews() = preview; override fun settings() = options; override fun preferences() = prefs
        override fun header(preferences: BookshelfHomePreferences) = head
        override suspend fun book(key: String): Book? = null; override suspend fun group(id: Long): BookGroup? = null
        fun emit(group: Long, books: List<Book>) { assertTrue(byGroup.getOrPut(group) { MutableSharedFlow(replay = 1) }.tryEmit(books)) }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun runModelTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.forEach { it.stop() }; runCurrent() }
    }
    private fun model(repo: Repository, saved: SavedStateHandle = SavedStateHandle(), format: (Long) -> String = { "time-$it" }) =
        BookshelfFolderViewModel(repo, saved, dispatcher, format).also { models += it }
    private fun books() = listOf(Book(bookUrl = "a", name = "Beta", author = "Zed", latestChapterTime = 10, durChapterTime = 20, order = 3,
        totalChapterNum = 11, durChapterIndex = 5), Book(bookUrl = "b", name = "Alpha", author = "Amy", latestChapterTime = 30, durChapterTime = 10, order = 1))
    private fun preview(index: Int) = BookshelfBook("p$index", "https://source", "Name $index", "Author $index", "cover$index", null,
        BookType.text, 1, true, index.toLong(), index.toLong(), index)
    @Test fun rootShowsGroupsAndBooksAndGroupEntryResetsRefreshBeforeNewDataArrives() = runModelTest {
        val repo = Repository(); repo.emit(BookGroup.IdRoot, books()); val model = model(repo); model.start(); runCurrent()
        assertEquals(4, model.state.value.itemCount); assertTrue(model.state.value.canRefresh); assertFalse(model.state.value.onlyRead)
        model.openGroup(2); assertTrue(model.getBooks().isEmpty()); assertTrue(model.state.value.books.isEmpty()); assertFalse(model.state.value.canRefresh)
        repo.emit(2, books()); runCurrent(); assertEquals(2, model.state.value.itemCount); assertFalse(model.state.value.canRefresh); assertTrue(model.state.value.onlyRead)
        assertTrue(model.back()); runCurrent(); assertFalse(model.state.value.onlyRead); assertTrue(model.state.value.canRefresh); assertFalse(model.back())
    }
    @Test fun rootPreviewsSelectFourByGroupSortAndCustomCoverSuppressesThem() = runModelTest {
        val repo = Repository(); repo.preview.value = (1..5).reversed().map(::preview); repo.emit(BookGroup.IdRoot, emptyList())
        val model = model(repo); model.start(); runCurrent()
        assertEquals(listOf("p1", "p2", "p3", "p4"), model.state.value.groups.first().preview.map { it.bookUrl })
        val snapshot = model.state.value.groups.first()
        repo.visible.value = listOf(BookGroup(1, "One", cover = "custom", bookSort = 3)); runCurrent()
        assertTrue(model.state.value.groups.single().preview.isEmpty()); assertEquals("custom", model.state.value.groups.single().cover)
        assertEquals(4, snapshot.preview.size)
    }
    @Test fun everySortIncludingAuthorAppliesToGroupBooksAndRootBooks() = runModelTest {
        val repo = Repository(); repo.emit(BookGroup.IdRoot, books()); val model = model(repo); model.start(); runCurrent()
        val expected = listOf(listOf("a", "b"), listOf("b", "a"), listOf("b", "a"), listOf("b", "a"), listOf("b", "a"), listOf("b", "a"))
        for (sort in 0..5) { repo.prefs.value = repo.prefs.value.copy(sort = sort); runCurrent(); assertEquals(expected[sort], model.state.value.books.map { it.card.key }) }
        repo.emit(1, books()); model.openGroup(1); runCurrent(); assertEquals(listOf("b", "a"), model.state.value.books.map { it.card.key })
        repo.visible.value = repo.visible.value.map { if (it.groupId == 1L) it.copy(bookSort = 5) else it }; runCurrent()
        assertEquals(listOf("b", "a"), model.state.value.books.map { it.card.key })
    }
    @Test fun selectedGroupRestoresAndSwipeRespectsRootAndGroupEdges() = runModelTest {
        val repo = Repository(); repo.emit(2, books()); repo.emit(1, books()); repo.emit(BookGroup.IdRoot, books())
        val saved = SavedStateHandle(mapOf("folder.group" to 2L)); val model = model(repo, saved); model.start(); runCurrent()
        assertTrue(model.state.value.previous); assertFalse(model.state.value.next)
        model.swipe(1); assertEquals(2L, model.state.value.groupId); model.swipe(-1); runCurrent(); assertEquals(1L, model.state.value.groupId)
        val restored = model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })); restored.start(); runCurrent()
        assertEquals(1L, restored.state.value.groupId); restored.back(); runCurrent(); restored.swipe(1)
        assertEquals(BookGroup.IdRoot, restored.state.value.groupId)
    }
    @Test fun deletingSelectedGroupReturnsToRootAndMetadataChangesUpdateRefreshFlags() = runModelTest {
        val repo = Repository(); repo.emit(1, books()); repo.emit(BookGroup.IdRoot, books()); val model = model(repo, SavedStateHandle(mapOf("folder.group" to 1L)))
        model.start(); runCurrent(); repo.visible.value = listOf(BookGroup(1, "Renamed", enableRefresh = false, onlyUpdateRead = true)); runCurrent()
        assertEquals("Renamed", model.state.value.selectedGroup?.name); assertFalse(model.state.value.canRefresh); assertTrue(model.state.value.onlyRead)
        repo.visible.value = emptyList(); runCurrent(); assertEquals(BookGroup.IdRoot, model.state.value.groupId); assertFalse(model.state.value.onlyRead)
    }
    @Test fun snapshotsAndReturnedBooksCannotBeMutatedByRepositoryOrCallers() = runModelTest {
        val repo = Repository(); val book = books().first(); repo.emit(BookGroup.IdRoot, listOf(book)); val model = model(repo); model.start(); runCurrent()
        book.name = "External"; model.getBooks().single().name = "Caller"
        assertEquals("Beta", model.getBook("a")?.name); assertEquals("Beta", model.state.value.books.single().card.name)
        repo.emit(BookGroup.IdRoot, listOf(book.copy())); runCurrent(); assertEquals("External", model.state.value.books.single().card.name)
    }
    @Test fun badgesProgressAndUpdateTimesReactToOptionsWithoutLosingBookIdentity() = runModelTest {
        val repo = Repository(); repo.emit(BookGroup.IdRoot, books() + Book(bookUrl = "local", type = BookType.local or BookType.text))
        repo.options.value = BookshelfPageSettings(showLatestUpdate = true); val model = model(repo); model.setUpdating("a", true); model.setUpdating("local", true)
        model.start(); runCurrent(); val first = model.state.value.books.first { it.card.key == "a" }.card
        assertTrue(first.updating); assertEquals("50%", first.progressPercent); assertEquals("time-10", first.latestUpdateLabel)
        assertFalse(model.state.value.books.first { it.card.key == "local" }.card.updating)
        repo.options.value = BookshelfPageSettings(showUnread = false, readProgressMode = 0, layout = 3, showLatestUpdate = true); runCurrent()
        assertTrue(model.state.value.books.all { it.card.readProgress == null && it.card.unreadCount == 0 && it.card.latestUpdateLabel == null })
    }
    @Test fun tickerStopsWithLifecycleAndScrollAcknowledgmentsCannotConsumeAnotherGroupOrToken() = runModelTest {
        val repo = Repository(); repo.emit(BookGroup.IdRoot, books()); repo.emit(1, books()); repo.options.value = BookshelfPageSettings(showLatestUpdate = true)
        var formats = 0; val model = model(repo, format = { formats++; "age" }); model.start(); runCurrent()
        val initial = formats; advanceTimeBy(30000); runCurrent(); assertTrue(formats > initial)
        model.gotoTop(); val first = model.state.value.scrollRequest; model.scrolled(BookGroup.IdRoot, first); model.gotoTop(); val second = model.state.value.scrollRequest
        model.scrolled(BookGroup.IdRoot, first); assertEquals(second, model.state.value.scrollRequest)
        model.openGroup(1); runCurrent(); model.gotoTop(); val third = model.state.value.scrollRequest
        model.scrolled(BookGroup.IdRoot, third); assertEquals(third, model.state.value.scrollRequest)
        model.stop(); val stopped = formats; advanceTimeBy(60000); runCurrent(); assertEquals(stopped, formats)
    }
    @Test fun loadingFailureRetriesAndHeaderOptionsProjectIndependentlyFromBookProgress() = runModelTest {
        val repo = Repository(); repo.fail = true; val model = model(repo); model.start(); runCurrent(); assertEquals("failed", model.state.value.error)
        repo.fail = false; repo.emit(BookGroup.IdRoot, books()); repo.options.value = BookshelfPageSettings(readProgressMode = 0)
        repo.prefs.value = BookshelfHomePreferences(stats = true, recent = true); repo.head.value = BookshelfHomeHeader(8, 2, books().first())
        model.retry(); runCurrent(); assertNull(model.state.value.error)
        assertEquals(8 to 2, model.state.value.header.stats); assertEquals("50%", model.state.value.header.recent?.progressPercent)
        assertTrue(model.state.value.books.all { it.card.readProgress == null })
    }
}
