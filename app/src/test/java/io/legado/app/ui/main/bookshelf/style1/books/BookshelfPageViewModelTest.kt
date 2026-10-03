package io.legado.app.ui.main.bookshelf.style1.books

import androidx.lifecycle.SavedStateHandle
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookshelfPageRepository
import io.legado.app.data.repository.BookshelfPageSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfPageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookshelfPageViewModel>()

    private class Repository : BookshelfPageRepository {
        val groups = mutableMapOf<Long, MutableSharedFlow<List<Book>>>()
        val preferences = MutableStateFlow(BookshelfPageSettings())
        var failure = false

        override fun books(groupId: Long): Flow<List<Book>> =
            if (failure) flow { error("load failed") }
            else groups.getOrPut(groupId) { MutableSharedFlow(replay = 1) }

        override fun settings() = preferences

        fun emit(group: Long, books: List<Book>) {
            assertTrue(groups.getOrPut(group) { MutableSharedFlow(replay = 1) }.tryEmit(books))
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        models.forEach { it.stop() }
        Dispatchers.resetMain()
    }

    private fun runModelTest(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    private fun model(repo: Repository, handle: SavedStateHandle = SavedStateHandle()) =
        BookshelfPageViewModel(repo, handle, dispatcher) { "age-$it" }.also { models += it }

    private fun books() =
        listOf(
            Book(
                bookUrl = "a",
                name = "Beta",
                author = "Zed",
                totalChapterNum = 11,
                durChapterIndex = 5,
                latestChapterTime = 10,
                durChapterTime = 20,
                order = 3,
            ),
            Book(
                bookUrl = "b",
                name = "Alpha",
                author = "Amy",
                latestChapterTime = 30,
                durChapterTime = 10,
                order = 1,
            ),
        )

    @Test
    fun allSixSortsUseLatestNameCustomCombinedAuthorAndRecentContracts() = runModelTest {
        val repo = Repository()
        repo.emit(-1, books())
        val model = model(repo)
        model.start()
        runCurrent()
        val expected =
            listOf(
                listOf("a", "b"),
                listOf("b", "a"),
                listOf("b", "a"),
                listOf("b", "a"),
                listOf("b", "a"),
                listOf("b", "a"),
            )
        for (sort in 0..5) {
            model.upSort(sort)
            runCurrent()
            assertEquals(expected[sort], model.state.value.entries.map { it.key })
        }
    }

    @Test
    fun roomUpdatesBecomeImmutableSnapshotsAndCallersCannotMutateStoredBooks() = runModelTest {
        val repo = Repository()
        val book = books().first()
        repo.emit(-1, listOf(book))
        val model = model(repo)
        model.start()
        runCurrent()
        val snapshot = model.state.value.entries.single()
        book.name = "Changed"
        model.getBooks().single().name = "Caller edit"
        assertEquals("Beta", model.getBook("a")!!.name)
        assertEquals("Beta", snapshot.name)
        repo.emit(-1, listOf(book.copy()))
        runCurrent()
        assertEquals("Changed", model.state.value.entries.single().name)
    }

    @Test
    fun optionsAndLoadingEventsProjectUnreadProgressLocalAndUpdateLabels() = runModelTest {
        val repo = Repository()
        repo.emit(-1, books() + Book(bookUrl = "local", type = BookType.local or BookType.text))
        repo.preferences.value = BookshelfPageSettings(showLatestUpdate = true)
        val model = model(repo)
        model.start()
        model.replaceUpdating(setOf("a", "local"))
        runCurrent()
        assertTrue(model.state.value.entries.first { it.key == "a" }.updating)
        assertEquals(5, model.state.value.entries.first { it.key == "a" }.unread)
        assertEquals(.5f, model.state.value.entries.first { it.key == "a" }.progress ?: 0f, 0f)
        assertEquals("age-10", model.state.value.entries.first { it.key == "a" }.latestUpdate)
        assertFalse(model.state.value.entries.first { it.key == "local" }.updating)
        assertNull(model.state.value.entries.first { it.key == "local" }.latestUpdate)
        repo.preferences.value =
            repo.preferences.value.copy(showUnread = false, readProgressMode = 0, layout = 3)
        model.setUpdating("a", false)
        runCurrent()
        assertTrue(
            model.state.value.entries.all {
                it.unread == 0 && it.progress == null && it.latestUpdate == null
            }
        )
        assertFalse(model.state.value.entries.first { it.key == "a" }.updating)
    }

    @Test
    fun parameterChangesRestoreAndGroupSwitchCannotRefreshPreviousGroupBooks() = runModelTest {
        val repo = Repository()
        val handle =
            SavedStateHandle(
                mapOf(
                    "position" to 3,
                    "groupId" to 8L,
                    "bookSort" to 2,
                    "enableRefresh" to true,
                    "onlyUpdateRead" to false,
                )
            )
        repo.emit(8, books())
        val model = model(repo, handle)
        model.start()
        runCurrent()
        assertTrue(model.state.value.canRefresh)
        model.setEnableRefresh(false)
        model.setOnlyUpdateRead(true)
        model.upSort(5)
        runCurrent()
        assertFalse(model.state.value.canRefresh)
        val restored =
            model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals(model.state.value.parameters, restored.state.value.parameters)
        model.configure(model.state.value.parameters.copy(groupId = 16, enableRefresh = true))
        assertTrue(model.getBooks().isEmpty())
        assertTrue(model.state.value.entries.isEmpty())
        assertFalse(model.state.value.canRefresh)
        repo.emit(16, listOf(Book(bookUrl = "next")))
        runCurrent()
        assertEquals(listOf("next"), model.state.value.entries.map { it.key })
    }

    @Test
    fun stoppingCancelsCollectionAndResumingLoadsMissedRoomAndPreferenceUpdates() = runModelTest {
        val repo = Repository()
        repo.emit(-1, books())
        val model = model(repo)
        model.start()
        model.start()
        runCurrent()
        model.stop()
        model.stop()
        runCurrent()
        repo.emit(-1, emptyList())
        repo.preferences.value = BookshelfPageSettings(layout = 4)
        runCurrent()
        assertEquals(2, model.state.value.entries.size)
        model.start()
        runCurrent()
        assertTrue(model.state.value.entries.isEmpty())
        assertEquals(4, model.state.value.settings.layout)
    }

    @Test
    fun failedLoadCanRetryWithoutLosingGroupParameters() = runModelTest {
        val repo = Repository()
        repo.failure = true
        val model = model(repo, SavedStateHandle(mapOf("groupId" to 16L)))
        model.start()
        runCurrent()
        assertEquals("load failed", model.state.value.error)
        assertFalse(model.state.value.loading)
        repo.failure = false
        repo.emit(16, books())
        model.retry()
        runCurrent()
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.entries.size)
        assertEquals(16L, model.state.value.parameters.groupId)
    }

    @Test
    fun updateAgeTickerRunsOnlyWhileResumedAndNonGridDisplayEnabled() = runModelTest {
        val repo = Repository()
        repo.emit(-1, books())
        repo.preferences.value = BookshelfPageSettings(showLatestUpdate = true)
        var calls = 0
        val model =
            BookshelfPageViewModel(repo, SavedStateHandle(), dispatcher) { "revision-${++calls}" }
                .also { models += it }
        model.start()
        runCurrent()
        val initial = calls
        advanceTimeBy(30000)
        runCurrent()
        assertTrue(calls > initial)
        model.stop()
        runCurrent()
        val paused = calls
        advanceTimeBy(60000)
        runCurrent()
        assertEquals(paused, calls)
        repo.preferences.value = repo.preferences.value.copy(layout = 3)
        model.start()
        runCurrent()
        val grid = calls
        advanceTimeBy(30000)
        runCurrent()
        assertEquals(grid, calls)
    }

    @Test
    fun completedScrollTokensAreNeverReusedAndLateAcknowledgementCannotConsumeNext() {
        val repo = Repository()
        val handle = SavedStateHandle()
        val model = model(repo, handle)
        model.gotoTop()
        val first = model.state.value.scrollRequest
        model.scrolled(first)
        model.gotoTop()
        val second = model.state.value.scrollRequest
        assertTrue(second > first)
        model.scrolled(first)
        assertEquals(second, model.state.value.scrollRequest)
        model.scrolled(second)
        val restored =
            model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.gotoTop()
        assertTrue(restored.state.value.scrollRequest > second)
        restored.scrolled(second)
        assertTrue(restored.state.value.scrollRequest > second)
    }

    @Test
    fun gotoTopRequestsSurviveProcessAndOldAcknowledgementsDoNotConsumeNewRequests() {
        val repo = Repository()
        val handle = SavedStateHandle()
        val model = model(repo, handle)
        model.gotoTop()
        val previous = model.state.value.scrollRequest
        model.gotoTop()
        val restored =
            model(repo, SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        restored.scrolled(previous)
        assertEquals(2, restored.state.value.scrollRequest)
        restored.scrolled(2)
        restored.scrolled(2)
        assertEquals(0, restored.state.value.scrollRequest)
    }
}
