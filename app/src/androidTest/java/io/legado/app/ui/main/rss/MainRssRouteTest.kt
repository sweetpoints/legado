package io.legado.app.ui.main.rss

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.RssSource
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class MainRssRouteTest {
    @get:Rule val compose = createComposeRule()
    private var model: MainRssViewModel? = null

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private class Repo : MainRssRepository {
        override fun rows(query: String) =
            MutableStateFlow(listOf(MainRssRow("id", "origin", "Feed", null, false)))

        override fun groups() = MutableStateFlow(listOf("A"))

        override suspend fun source(id: String) =
            RssSource(sourceUrl = "origin", sourceName = "Feed")

        override suspend fun top(id: String) {}

        override suspend fun disable(id: String) {}

        override suspend fun delete(id: String) {}

        override suspend fun prepare(id: String) =
            MainRssNavigation(MainRssDestination.ReaderHtml, "origin", "Feed", "Large body")
    }

    private class Sessions : MainRssSessionRepository {
        var value: MainRssCheckpoint? = null

        override suspend fun read(id: String) = value

        override suspend fun write(id: String, checkpoint: MainRssCheckpoint) {
            value = checkpoint
        }

        override suspend fun release(id: String) {}
    }

    private class Launches : RssReaderLaunchRepository {
        val staged = mutableListOf<RssReaderRequest>()
        val released = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false

        override suspend fun stage(request: RssReaderRequest): String {
            if (fail) error("Disk failed")
            staged += request
            withContext(NonCancellable) { gate?.await() }
            return "ticket-${staged.size}"
        }

        override suspend fun read(ticket: String): RssReaderRequest? = null

        override suspend fun release(ticket: String) {
            released += ticket
        }
    }

    private val images =
        object : RssArticleImageRepository {
            override suspend fun ratio(source: String): Float? = null

            override suspend fun load(
                source: String,
                origin: String,
                width: Int,
                height: Int,
                natural: Boolean,
            ): AnimatedDrawableResource? = null
        }

    @After
    fun stop() {
        compose.runOnIdle { model?.stop() }
    }

    private fun show(
        owner: Owner,
        launches: Launches,
        sessions: Sessions = Sessions(),
        native: (MainRssPrepared, String?) -> Unit,
    ) {
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model = MainRssViewModel(Repo(), sessions, SavedStateHandle())
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                LegadoComposeTheme {
                    MainRssRoute(
                        checkNotNull(model),
                        images,
                        { owner.lifecycle.currentState == Lifecycle.State.RESUMED },
                        native,
                        launches,
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            model!!.state.value.loaded && model!!.state.value.rows.isNotEmpty()
        }
    }

    @Test
    fun navigationStagesExactPrivateInputAndConsumesOnceBeforeHostCall() {
        val calls = mutableListOf<String?>()
        val launches = Launches()
        val owner = Owner()
        show(owner, launches) { _, ticket ->
            assertNull(model!!.state.value.pending)
            calls += ticket
        }
        compose.onNodeWithTag("main-rss-card-id").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { calls.size == 1 }
        assertEquals(listOf("ticket-1"), calls)
        assertEquals("Large body", launches.staged.single().startHtml)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(1, calls.size)
    }

    @Test
    fun pauseWhileStagingKeepsPendingCleansTicketAndResumeDeliversFreshTicket() {
        val gate = CompletableDeferred<Unit>()
        val launches = Launches().apply { this.gate = gate }
        val owner = Owner()
        val calls = mutableListOf<String?>()
        try {
            show(owner, launches) { _, ticket -> calls += ticket }
            compose.onNodeWithTag("main-rss-card-id").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { launches.staged.size == 1 }
            compose.runOnIdle {
                owner.registry.currentState = Lifecycle.State.STARTED
                gate.complete(Unit)
            }
            compose.waitUntil(timeoutMillis = 10_000) { launches.released.isNotEmpty() }
            assertTrue(calls.isEmpty())
            assertNotNull(model!!.state.value.pending)
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(timeoutMillis = 10_000) { calls.size == 1 }
            assertEquals(listOf("ticket-2"), calls)
            assertEquals(listOf("ticket-1"), launches.released)
        } finally {
            gate.complete(Unit)
        }
    }

    @Test
    fun inactivePagerHidesRestoredDeleteModalAndStageErrorCanRetrySamePending() {
        val owner = Owner()
        val launches = Launches().apply { fail = true }
        val calls = mutableListOf<String?>()
        show(owner, launches) { _, ticket -> calls += ticket }
        compose.runOnIdle { model!!.requestDelete("id") }
        compose.onNodeWithTag("main-rss-delete-confirm").assertIsDisplayed()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithTag("main-rss-delete-confirm").assertDoesNotExist()
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.RESUMED
            model!!.cancelDelete()
        }
        compose.onNodeWithTag("main-rss-card-id").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { model!!.state.value.error != null }
        assertNotNull(model!!.state.value.pending)
        assertTrue(calls.isEmpty())
        launches.fail = false
        compose.onNodeWithTag("main-rss-retry").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { calls.size == 1 }
        assertEquals(listOf("ticket-1"), calls)
    }
}
