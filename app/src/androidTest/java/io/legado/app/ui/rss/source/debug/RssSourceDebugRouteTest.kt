package io.legado.app.ui.rss.source.debug

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class RssSourceDebugRouteTest {
    @get:Rule val compose = createComposeRule()
    private val models = mutableListOf<RssSourceDebugViewModel>()
    private val repos = mutableListOf<Fake>()

    @After
    fun after() {
        compose.runOnIdle {
            models.forEach {
                it.stop()
                it.viewModelScope.cancel()
            }
            repos.forEach { it.leases.forEach { lease -> lease.done.complete(Unit) } }
        }
    }

    private fun model(repo: Fake): RssSourceDebugViewModel {
        lateinit var result: RssSourceDebugViewModel
        compose.runOnIdle {
            result = RssSourceDebugViewModel(repo, SavedStateHandle(), "key")
            models += result
            repos += repo
        }
        return result
    }

    @Test
    fun pausedScreenKeepsExecutionAndResumeShowsLogsAndRerunWaitsForCanceledExecutionToFinish() {
        val repo = Fake()
        val model = model(repo)
        val owner = Owner()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme { RssSourceDebugRoute(model, {}, {}, { error(it) }) }
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.run("query") }
        compose.waitUntil { repo.leases.size == 1 }
        val old = repo.leases.single()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.CREATED
            old.event(RssSourceDebugEvent(1, "while paused"))
        }
        assertEquals(0, old.closes)
        assertTrue(model.state.value.running)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("while paused").assertExists()
        compose.onNodeWithTag("rss-debug-search").performClick()
        compose.runOnIdle {
            assertEquals(1, old.closes)
            assertEquals(1, repo.leases.size)
            old.event(RssSourceDebugEvent(1, "late"))
            old.done.complete(Unit)
        }
        compose.waitUntil { repo.leases.size == 2 }
        compose.runOnIdle { repo.leases.last().event(RssSourceDebugEvent(1, "fresh")) }
        compose.onNodeWithText("fresh").assertExists()
        compose.onNodeWithText("late").assertDoesNotExist()
    }

    @Test
    fun backImmediatelyReleasesOwnedExecutionBeforeClosingHost() {
        val repo = Fake()
        val model = model(repo)
        var backs = 0
        compose.setContent {
            LegadoComposeTheme {
                RssSourceDebugRoute(
                    model,
                    {
                        assertEquals(1, repo.leases.single().closes)
                        backs++
                    },
                    {},
                    { error(it) },
                )
            }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.run("query") }
        compose.waitUntil { repo.leases.size == 1 }
        compose.onNodeWithTag("rss-debug-back").performClick()
        compose.waitUntil { backs == 1 }
        assertTrue(model.state.value.closed)
    }

    @Test
    fun missingHostDefersCloseWhilePausedAndOnlyClosesOnceAfterResume() {
        val repo = Fake()
        repo.missing = true
        val model = model(repo)
        val owner = Owner()
        var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme { RssSourceDebugRoute(model, { closes++ }, {}, { error(it) }) }
            }
        }
        compose.waitUntil { model.state.value.missing }
        assertEquals(0, closes)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { closes == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun htmlBodyFromParserEventIsDeliveredByEachMenuWithoutStoppingActiveExecution() {
        val repo = Fake()
        val model = model(repo)
        val bodies = mutableListOf<String?>()
        compose.setContent {
            LegadoComposeTheme { RssSourceDebugRoute(model, {}, { bodies += it }, { error(it) }) }
        }
        compose.waitUntil { model.state.value.loaded }
        compose.runOnIdle { model.run("query") }
        compose.waitUntil { repo.leases.size == 1 }
        val lease = repo.leases.single()
        compose.runOnIdle {
            lease.event(RssSourceDebugEvent(10, "list fixture"))
            lease.event(RssSourceDebugEvent(20, "content fixture"))
        }
        compose.onNodeWithTag("rss-debug-menu").performClick()
        compose.onNodeWithTag("rss-debug-list-html").performClick()
        compose.onNodeWithTag("rss-debug-menu").performClick()
        compose.onNodeWithTag("rss-debug-content-html").performClick()
        assertEquals(listOf("list fixture", "content fixture"), bodies)
        assertEquals(0, lease.closes)
        assertTrue(model.state.value.running)
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle
            get() = registry
    }

    private class Fake : RssSourceDebugRepository {
        var missing = false
        val leases = mutableListOf<Lease>()
        val records = mutableMapOf<String, RssSourceDebugRecord>()

        override suspend fun load(key: String) =
            if (missing) null else RssSourceDebugSnapshot(key, "Name", "json")

        override suspend fun sorts(source: RssSourceDebugSnapshot) = emptyList<RssSourceDebugSort>()

        override suspend fun acquire(
            source: RssSourceDebugSnapshot,
            event: (RssSourceDebugEvent) -> Unit,
        ) = Lease(event).also { leases += it }

        override suspend fun read(session: String) = records[session]

        override suspend fun write(session: String, record: RssSourceDebugRecord) {
            if ((records[session]?.revision ?: -1) <= record.revision) records[session] = record
        }
    }

    private class Lease(val event: (RssSourceDebugEvent) -> Unit) : RssSourceDebugLease {
        val done = CompletableDeferred<Unit>()
        var closes = 0

        override suspend fun run(query: String) = withContext(NonCancellable) { done.await() }

        override fun close() {
            if (closes == 0) closes++
        }

        override suspend fun awaitStopped() = Unit
    }
}
