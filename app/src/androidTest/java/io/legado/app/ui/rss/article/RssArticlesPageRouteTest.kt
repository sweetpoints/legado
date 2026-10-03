package io.legado.app.ui.rss.article

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.image.AnimatedDrawableResource
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class RssArticlesPageRouteTest {
    @get:Rule val compose = createComposeRule()
    private val parameters = RssArticlesParameters("source", "Category", "url")
    private lateinit var model: RssArticlesPageViewModel
    private lateinit var owner: RssPageLifecycleOwner
    private var active by mutableStateOf(false)
    private val delivered = mutableListOf<RssArticlesRead>()
    private var gate: CompletableDeferred<Unit>? = null
    private var missing = false
    private var throwCallback = false
    private var prepares = 0
    private val repo =
        object : RssArticlesPageRepository {
            override fun observe(parameters: RssArticlesParameters) =
                MutableStateFlow(listOf(RssArticleRow("one", "One", null, null, false, "source")))

            override suspend fun fetch(parameters: RssArticlesParameters, url: String, page: Int) =
                error("Restored page must not fetch")

            override suspend fun refresh(
                parameters: RssArticlesParameters,
                batch: RssArticlesBatch,
                order: Long,
            ) = error("Unused")

            override suspend fun append(
                parameters: RssArticlesParameters,
                batch: RssArticlesBatch,
                order: Long,
            ) = error("Unused")

            override suspend fun resolve(
                parameters: RssArticlesParameters,
                key: String,
            ): RssArticle? = error("Must use prepared read")
        }
    private val sessions =
        object : RssArticlesSessionRepository {
            override suspend fun read(session: String) =
                RssArticlesSession(parameters, initialized = true, revision = 1)

            override suspend fun write(session: String, value: RssArticlesSession) {}

            override suspend fun release(session: String) {}
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

    private fun show() {
        compose.runOnIdle {
            owner = RssPageLifecycleOwner().also { it.update(Lifecycle.State.RESUMED, active) }
            model = RssArticlesPageViewModel(repo, sessions, SavedStateHandle())
        }
        compose.setContent {
            LegadoComposeTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    RssArticlesPageRoute(
                        model,
                        parameters,
                        0,
                        false,
                        active,
                        { true },
                        {
                            delivered += it
                            if (throwCallback) error("Native callback failure")
                        },
                        images,
                        RssArticlesReadRepository { _, key ->
                            prepares++
                            withContext(NonCancellable) { gate?.await() }
                            if (missing) null
                            else
                                RssArticlesRead(
                                    RssArticle(
                                        origin = "source",
                                        link = key,
                                        title = "Latest",
                                        variable = "Metadata",
                                    ),
                                    null,
                                )
                        },
                    )
                }
            }
        }
        compose.waitUntil { model.state.value.loaded }
    }

    private fun visible(value: Boolean) {
        compose.runOnIdle {
            active = value
            owner.update(Lifecycle.State.RESUMED, value)
        }
    }

    @After
    fun cleanup() {
        if (::model.isInitialized)
            compose.runOnIdle {
                gate?.complete(Unit)
                model.stop()
                owner.dispose()
            }
    }

    @Test
    fun inactivePageKeepsPendingTicketAndOnlyResumedVisiblePageCanDeliverOnce() {
        show()
        compose.runOnIdle { model.open("one") }
        compose.waitForIdle()
        assertEquals(0, prepares)
        assertTrue(delivered.isEmpty())
        visible(true)
        compose.waitUntil { delivered.size == 1 }
        assertEquals("Metadata", delivered.single().article.variable)
        assertNull(model.state.value.open)
        visible(false)
        visible(true)
        compose.waitForIdle()
        assertEquals(1, delivered.size)
    }

    @Test
    fun nonCooperativeResolutionWhileHiddenKeepsTicketAndResolvesAgainWhenResumed() {
        active = true
        gate = CompletableDeferred()
        show()
        compose.runOnIdle { model.open("one") }
        compose.waitUntil { prepares == 1 }
        visible(false)
        compose.runOnIdle { gate!!.complete(Unit) }
        compose.waitForIdle()
        assertTrue(delivered.isEmpty())
        assertNotNull(model.state.value.open)
        visible(true)
        compose.waitUntil { delivered.size == 1 }
        assertEquals(2, prepares)
        assertNull(model.state.value.open)
    }

    @Test
    fun missingEntityConsumesTicketWithoutNavigationAndAllowsNextSelection() {
        active = true
        missing = true
        show()
        compose.runOnIdle { model.open("one") }
        compose.waitUntil { prepares == 1 && model.state.value.open == null }
        assertTrue(delivered.isEmpty())
        compose.runOnIdle {
            missing = false
            model.open("one")
        }
        compose.waitUntil { delivered.size == 1 }
        assertEquals(2, prepares)
    }

    @Test
    fun nativeFailureDoesNotReplayAlreadyDeliveredTicket() {
        active = true
        throwCallback = true
        show()
        compose.runOnIdle { model.open("one") }
        compose.waitUntil { model.state.value.error == "Native callback failure" }
        assertEquals(1, delivered.size)
        visible(false)
        visible(true)
        compose.waitForIdle()
        assertEquals(1, delivered.size)
    }
}
