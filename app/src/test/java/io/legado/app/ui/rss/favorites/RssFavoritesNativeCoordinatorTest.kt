package io.legado.app.ui.rss.favorites

import androidx.lifecycle.*
import io.legado.app.data.entities.RssStar
import io.legado.app.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class RssFavoritesNativeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<RssFavoriteListViewModel>()
    private val coordinators = mutableListOf<RssFavoritesNativeCoordinator>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun cleanup() {
        coordinators.forEach { it.close() }
        models.forEach {
            it.stop()
            it.viewModelScope.cancel()
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle
            get() = registry

        var ready = true
    }

    private class Repository : RssFavoriteListRepository {
        val star =
            RssStar(
                origin = "origin",
                link = "link",
                group = "group",
                title = "title",
                content = "PRIVATE_BODY",
            )
        val row = RoomRssFavoriteListRepository.row(star)
        var gate: CompletableDeferred<Unit>? = null
        var deletes = 0

        override fun observe() = flowOf(RssFavoriteListSnapshot(listOf("group"), listOf(row)))

        override suspend fun resolve(id: String): RssStar? {
            gate?.await()
            return star.takeIf { row.id == id }
        }

        override suspend fun delete(id: String) {
            deletes++
        }

        override suspend fun deleteGroup(group: String) {
            deletes++
        }

        override suspend fun deleteAll() {
            deletes++
        }
    }

    private fun model(repo: Repository) =
        RssFavoriteListViewModel(repo, SavedStateHandle()).also { models += it }

    private fun coordinator(
        model: RssFavoriteListViewModel,
        host: Host,
        scope: CoroutineScope,
        toolbar: Boolean = true,
        read: (RssStar) -> Unit = {},
    ) =
        RssFavoritesNativeCoordinator(
                model,
                host.lifecycle,
                scope,
                toolbar,
                { host.ready },
                read,
                {},
            )
            .also { coordinators += it }

    private fun command(
        coordinator: RssFavoritesNativeCoordinator,
        owner: Any,
        op: String,
        args: Map<String, Any?> = emptyMap(),
    ) = coordinator.command(owner, args + mapOf("generation" to coordinator.generation, "op" to op))

    @Test
    fun nativeBackDoesNotDependOnFirstSnapshotOrLoadedRows() =
        runTest(dispatcher) {
            val repo = Repository()
            val model = model(repo)
            val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
            var exits = 0
            val c =
                RssFavoritesNativeCoordinator(
                        model,
                        host.lifecycle,
                        backgroundScope,
                        true,
                        { true },
                        {},
                        { exits++ },
                    )
                    .also { coordinators += it }
            val owner = c.bind()
            assertFalse(c.snapshot(owner)["loaded"] as Boolean)
            command(c, owner, "back")
            assertEquals(1, exits)
        }

    @Test
    fun projectionOmitsPrivateArticleAndOriginAndRejectsRetiredBinding() =
        runTest(dispatcher) {
            val repo = Repository()
            val model = model(repo)
            val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
            val c = coordinator(model, host, backgroundScope)
            runCurrent()
            val owner = c.bind()
            val first = c.snapshot(owner)
            assertFalse(first.toString().contains("PRIVATE_BODY"))
            assertFalse(first.toString().contains("origin"))
            assertEquals(first, c.snapshot(owner))
            c.bind()
            assertThrows(IllegalStateException::class.java) { command(c, owner, "deleteAll") }
            assertEquals(0, repo.deletes)
        }

    @Test
    fun resumedReadAndHostReadyRaceDeliversCompleteNativeStarExactlyOnce() =
        runTest(dispatcher) {
            val repo = Repository().apply { gate = CompletableDeferred() }
            val model = model(repo)
            val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
            val delivered = mutableListOf<RssStar>()
            val c = coordinator(model, host, backgroundScope, read = { delivered += it })
            runCurrent()
            command(c, c.bind(), "read", mapOf("id" to repo.row.id))
            runCurrent()
            c.onHostReady(backgroundScope)
            repo.gate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(repo.star), delivered)
            assertNull(model.state.value.pendingRead)
        }

    @Test
    fun pausedOrSavedHostDefersPendingReadAndRetryAfterResumeDoesNotReplay() =
        runTest(dispatcher) {
            val repo = Repository()
            val model = model(repo)
            val host =
                Host().apply {
                    registry.currentState = Lifecycle.State.STARTED
                    ready = false
                }
            var reads = 0
            val c = coordinator(model, host, backgroundScope, read = { reads++ })
            runCurrent()
            command(c, c.bind(), "read", mapOf("id" to repo.row.id))
            c.onHostReady(backgroundScope)
            runCurrent()
            assertEquals(0, reads)
            assertNotNull(model.state.value.pendingRead)
            host.registry.currentState = Lifecycle.State.RESUMED
            runCurrent()
            assertEquals(0, reads)
            host.ready = true
            c.onHostReady(backgroundScope)
            runCurrent()
            assertEquals(1, reads)
            c.onHostReady(backgroundScope)
            runCurrent()
            assertEquals(1, reads)
        }

    @Test
    fun embeddedBindingCannotDeleteAllAndConfirmationRequiresExplicitNativeConfirm() =
        runTest(dispatcher) {
            val repo = Repository()
            val model = model(repo)
            val host = Host().apply { registry.currentState = Lifecycle.State.RESUMED }
            val c = coordinator(model, host, backgroundScope, toolbar = false)
            runCurrent()
            val owner = c.bind()
            assertThrows(IllegalArgumentException::class.java) { command(c, owner, "deleteAll") }
            command(c, owner, "delete", mapOf("id" to repo.row.id))
            runCurrent()
            assertNotNull(model.state.value.confirmation)
            assertEquals(0, repo.deletes)
            command(c, owner, "confirm")
            runCurrent()
            assertEquals(1, repo.deletes)
            assertNull(model.state.value.confirmation)
        }
}
