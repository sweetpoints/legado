package io.legado.app.ui.main.bookshelf.style1

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
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
class BookshelfHomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<BookshelfHomeViewModel>()

    private class Repository : BookshelfHomeRepository {
        val groupFlow =
            MutableStateFlow(listOf(BookGroup(1, "A"), BookGroup(2, "B"), BookGroup(4, "C")))
        val prefs = MutableStateFlow(BookshelfHomePreferences())
        val head = MutableStateFlow(BookshelfHomeHeader())
        val selections = mutableListOf<Int>()
        var enabled = 0
        var fail = false

        override fun groups(): Flow<List<BookGroup>> =
            if (fail) flow { error("failed") } else groupFlow

        override fun preferences() = prefs

        override fun header(preferences: BookshelfHomePreferences) = head

        override fun select(position: Int) {
            selections += position
            prefs.value = prefs.value.copy(selectedPosition = position)
        }

        override suspend fun enableAll() {
            enabled++
        }

        override suspend fun book(key: String): Book? = null

        override suspend fun group(id: Long): BookGroup? = null
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
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

    private fun model(repo: Repository, saved: SavedStateHandle = SavedStateHandle()) =
        BookshelfHomeViewModel(repo, saved).also { models += it }

    @Test
    fun restoredGroupWinsOverPositionAndSurvivesReorderAndTemporaryEmptyResult() = runModelTest {
        val repo = Repository()
        val saved = SavedStateHandle(mapOf("bookshelf.selectedId" to 2L))
        val model = model(repo, saved)
        model.start()
        runCurrent()
        assertEquals(2L, model.state.value.selectedId)
        repo.groupFlow.value = listOf(BookGroup(2, "B"), BookGroup(1, "A"))
        runCurrent()
        assertEquals(0, model.state.value.selectedIndex)
        repo.groupFlow.value = emptyList()
        runCurrent()
        assertEquals(1, repo.enabled)
        assertEquals(2L, saved.get<Long>("bookshelf.selectedId"))
        repo.groupFlow.value = listOf(BookGroup(1, "A"), BookGroup(2, "B"))
        runCurrent()
        assertEquals(2L, model.state.value.selectedId)
    }

    @Test
    fun persistedPositionClampsAndMissingSelectedGroupFallsBackSafely() = runModelTest {
        val repo = Repository()
        repo.prefs.value = BookshelfHomePreferences(selectedPosition = 99)
        val model = model(repo)
        model.start()
        runCurrent()
        assertEquals(4L, model.state.value.selectedId)
        repo.groupFlow.value = listOf(BookGroup(1, "A"))
        runCurrent()
        assertEquals(1L, model.state.value.selectedId)
        model.select(999)
        assertTrue(repo.selections.isEmpty())
    }

    @Test
    fun selectPersistsIndexAndProcessRestoresTheSelectedIdentity() = runModelTest {
        val repo = Repository()
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        model.start()
        runCurrent()
        model.select(2)
        runCurrent()
        assertEquals(listOf(1), repo.selections)
        val restored =
            model(repo, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        restored.start()
        runCurrent()
        assertEquals(2L, restored.state.value.selectedId)
    }

    @Test
    fun groupConfigurationUpdatesKeepSelectionAndResolveInheritedSort() = runModelTest {
        val repo = Repository()
        repo.prefs.value = repo.prefs.value.copy(sort = 5)
        val model = model(repo)
        model.start()
        runCurrent()
        assertEquals(5, model.state.value.groups.first().sort)
        repo.groupFlow.value =
            listOf(
                BookGroup(1, "Renamed", bookSort = 3, enableRefresh = false, onlyUpdateRead = true)
            )
        runCurrent()
        assertEquals(
            BookshelfHomeGroup(1, "Renamed", 3, false, true),
            model.state.value.selectedGroup,
        )
    }

    @Test
    fun headerProjectsStatsAndRecentProgressIndependentlyFromListOptions() = runModelTest {
        val repo = Repository()
        repo.head.value =
            BookshelfHomeHeader(
                7,
                3,
                Book(
                    bookUrl = "recent",
                    name = "Reading",
                    totalChapterNum = 11,
                    durChapterIndex = 5,
                ),
            )
        val model = model(repo)
        model.start()
        runCurrent()
        assertNull(model.state.value.header.stats)
        assertNull(model.state.value.header.recent)
        repo.prefs.value = repo.prefs.value.copy(stats = true, recent = true)
        runCurrent()
        assertEquals(7 to 3, model.state.value.header.stats)
        assertEquals("50%", model.state.value.header.recent?.progressPercent)
    }

    @Test
    fun loadFailureCanRetryAndStoppedModelDoesNotReceiveUpdates() = runModelTest {
        val repo = Repository()
        repo.fail = true
        val model = model(repo)
        model.start()
        runCurrent()
        assertEquals("failed", model.state.value.error)
        repo.fail = false
        model.retry()
        runCurrent()
        assertNull(model.state.value.error)
        model.stop()
        repo.groupFlow.value = listOf(BookGroup(8, "New"))
        runCurrent()
        assertEquals(1L, model.state.value.selectedId)
        model.start()
        runCurrent()
        assertEquals(8L, model.state.value.selectedId)
    }
}
