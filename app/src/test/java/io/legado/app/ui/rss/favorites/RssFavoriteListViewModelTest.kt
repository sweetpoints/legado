package io.legado.app.ui.rss.favorites

import androidx.lifecycle.SavedStateHandle
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssFavoriteListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssFavoriteListViewModel>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
    }

    private class Fake : RssFavoriteListRepository {
        val snapshots = MutableSharedFlow<RssFavoriteListSnapshot>(replay = 1)
        var latest = mutableMapOf<String, RssStar>()
        val deletes = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var uncooperative = false

        override fun observe(): Flow<RssFavoriteListSnapshot> = snapshots

        override suspend fun resolve(id: String): RssStar? {
            if (uncooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            return latest[id]?.copy()
        }

        private suspend fun remove(value: String) {
            deletes += value
            if (uncooperative) withContext(NonCancellable) { gate?.await() } else gate?.await()
            if (fail) error("failed")
        }

        override suspend fun delete(id: String) = remove("row:$id")

        override suspend fun deleteGroup(group: String) = remove("group:$group")

        override suspend fun deleteAll() = remove("all")

        fun emit(vararg stars: RssStar) {
            latest =
                stars
                    .associateBy { RoomRssFavoriteListRepository.key(it.origin, it.link) }
                    .toMutableMap()
            snapshots.tryEmit(
                RssFavoriteListSnapshot(
                    stars.map { it.group }.distinct().sorted(),
                    stars.map(RoomRssFavoriteListRepository::row),
                )
            )
        }
    }

    private fun star(group: String, link: String = group) =
        RssStar(
            origin = "origin",
            link = link,
            group = group,
            title = "Title $link",
            content = "Large ".repeat(1000),
        )

    private fun model(
        repo: Fake,
        saved: SavedStateHandle = SavedStateHandle(),
        group: String? = null,
    ) = RssFavoriteListViewModel(repo, saved, group).also { models += it }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun test(block: suspend TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                block()
            } finally {
                models.forEach { it.stop() }
                runCurrent()
            }
        }

    @Test
    fun lateInitialDataRestoresGroupAndKeepsSeparateScrollPositions() = test {
        val saved = SavedStateHandle(mapOf("favorites.group" to "B"))
        val repo = Fake()
        val first = model(repo, saved)
        runCurrent()
        assertFalse(first.state.value.loaded)
        first.read("not-ready")
        repo.emit(star("A"), star("B"))
        runCurrent()
        assertEquals("B", first.state.value.group)
        first.scrolled("B", 12, 31)
        first.selectGroup("A")
        first.scrolled("A", 2, 8)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals("A", restored.state.value.group)
        assertEquals(FavoriteScrollPosition(12, 31), restored.scrollPosition("B"))
        assertEquals(FavoriteScrollPosition(2, 8), restored.scrollPosition("A"))
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .all { it.length <= 100 }
        )
    }

    @Test
    fun groupRenameAndRemovalKeepSelectionByNameThenNearestIndex() = test {
        val repo = Fake()
        repo.emit(star("A"), star("B"), star("C"))
        val model = model(repo)
        runCurrent()
        model.selectGroup("B")
        repo.emit(star("0"), star("A"), star("B"), star("C"))
        runCurrent()
        assertEquals("B", model.state.value.group)
        repo.emit(star("A"), star("C"))
        runCurrent()
        assertEquals("C", model.state.value.group)
        repo.emit()
        runCurrent()
        assertNull(model.state.value.group)
        repo.emit(star(""))
        runCurrent()
        assertEquals("", model.state.value.group)
    }

    @Test
    fun fixedFragmentGroupRemainsExactEvenWhenEmptyAndCannotSwitch() = test {
        val repo = Fake()
        repo.emit(star("A"), star(""))
        val model = model(repo, group = "")
        runCurrent()
        assertEquals(listOf(""), model.state.value.groups)
        assertEquals("", model.state.value.group)
        assertEquals(1, model.state.value.visibleRows.size)
        model.selectGroup("A")
        assertEquals("", model.state.value.group)
        repo.emit(star("A"))
        runCurrent()
        assertTrue(model.state.value.visibleRows.isEmpty())
        assertEquals("", model.state.value.group)
    }

    @Test
    fun smallReadRequestRestoresAndResolvesUpdatedFullMetadataExactlyOnce() = test {
        val repo = Fake()
        val original = star("A")
        repo.emit(original)
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        val id = model.state.value.visibleRows.single().id
        model.read(id)
        model.read(id)
        repo.latest[id] =
            original.copy(title = "Latest", type = 2, durPos = 28, variable = "{data}")
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals("Latest", restored.resolveRead(id)?.title)
        assertEquals(id, restored.state.value.pendingRead)
        restored.readDelivered("wrong")
        assertEquals(id, restored.state.value.pendingRead)
        restored.readDelivered(id)
        assertNull(restored.resolveRead(id))
        assertNull(restored.state.value.pendingRead)
        assertTrue(
            saved
                .keys()
                .mapNotNull { saved.get<Any?>(it) }
                .filterIsInstance<String>()
                .all { it.length < 100 }
        )
    }

    @Test
    fun deletedArticleReadIsConsumedWithoutNavigationAndUnknownRowCannotQueue() = test {
        val repo = Fake()
        repo.emit(star("A"))
        val model = model(repo)
        runCurrent()
        model.read("unknown")
        assertNull(model.state.value.pendingRead)
        val id = model.state.value.visibleRows.single().id
        model.read(id)
        repo.latest.clear()
        assertNull(model.resolveRead(id))
        model.readDelivered(id)
        assertNull(model.state.value.pendingRead)
    }

    @Test
    fun confirmationUsesCapturedGroupAcrossTabSwitchAndCancelNeverDeletes() = test {
        val repo = Fake()
        repo.emit(star("A"), star("B"))
        val model = model(repo)
        runCurrent()
        model.requestDeleteGroup()
        model.selectGroup("B")
        model.confirmDelete()
        runCurrent()
        assertEquals(listOf("group:A"), repo.deletes)
        model.requestDeleteAll()
        model.cancelConfirmation()
        model.confirmDelete()
        runCurrent()
        assertEquals(1, repo.deletes.size)
        val id = model.state.value.visibleRows.single().id
        model.requestDelete(id)
        model.cancelConfirmation()
        assertNull(model.state.value.confirmation)
    }

    @Test
    fun confirmationRestoresSmallTargetAndDropsDisappearedRow() = test {
        val repo = Fake()
        repo.emit(star("A"))
        val saved = SavedStateHandle()
        val model = model(repo, saved)
        runCurrent()
        val id = model.state.value.visibleRows.single().id
        model.requestDelete(id)
        val restored = model(repo, copy(saved))
        runCurrent()
        assertEquals(id, restored.state.value.confirmation?.target)
        repo.emit()
        runCurrent()
        assertNull(restored.state.value.confirmation)
        restored.confirmDelete()
        assertTrue(repo.deletes.isEmpty())
    }

    @Test
    fun activeDeleteRejectsDuplicateAndFailureKeepsConfirmationRetryable() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                fail = true
            }
        repo.emit(star("A"))
        val model = model(repo)
        runCurrent()
        model.requestDeleteAll()
        model.confirmDelete()
        model.confirmDelete()
        model.cancelConfirmation()
        runCurrent()
        assertEquals(listOf("all"), repo.deletes)
        assertTrue(model.state.value.busy)
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals("failed", model.state.value.error)
        assertNotNull(model.state.value.confirmation)
        repo.fail = false
        model.confirmDelete()
        runCurrent()
        assertEquals(listOf("all", "all"), repo.deletes)
        assertNull(model.state.value.confirmation)
    }

    @Test
    fun stoppedDeleteCannotPublishLateCompletion() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                uncooperative = true
            }
        repo.emit(star("A"))
        val model = model(repo)
        runCurrent()
        model.requestDeleteAll()
        model.confirmDelete()
        runCurrent()
        val before = model.state.value
        model.stop()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertEquals(before, model.state.value)
    }

    @Test
    fun cancelledNonCooperativeResolutionKeepsPendingTicketForResumedRetry() = test {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                uncooperative = true
            }
        repo.emit(star("A"))
        val model = model(repo)
        runCurrent()
        val id = model.state.value.visibleRows.single().id
        model.read(id)
        var delivered = false
        val pausedDelivery = launch {
            model.resolveRead(id)
            model.readDelivered(id)
            delivered = true
        }
        runCurrent()
        pausedDelivery.cancel()
        repo.gate!!.complete(Unit)
        runCurrent()
        assertFalse(delivered)
        assertEquals(id, model.state.value.pendingRead)
        assertNotNull(model.resolveRead(id))
        model.readDelivered(id)
        assertNull(model.state.value.pendingRead)
    }
}
