package io.legado.app.ui.rss.article

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.*
import org.junit.Assert.*

class RssReadRecordScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: RssReadRecordViewModel

    private class Fake : RssReadRecordRepository {
        var items =
            listOf(RssReadRecordItem("key", "Title", "https://article.example.com", "origin"))
        var latest: RssReadRecord? =
            RssReadRecord(
                "https://article.example.com",
                "Latest",
                origin = "origin",
                durPos = 99,
                type = 2,
            )
        var gate: CompletableDeferred<Unit>? = null
        var clearGate: CompletableDeferred<Unit>? = null
        var fail = false
        var counts = 4
        var deletes = 0
        var resolveGate: CompletableDeferred<Unit>? = null
        var uncooperative = false
        var resolves = 0

        override suspend fun load(origin: String?): List<RssReadRecordItem> {
            gate?.await()
            if (fail) error("failed")
            return items
        }

        override suspend fun count(origin: String?) = counts

        override suspend fun clear(origin: String?) {
            deletes++
            clearGate?.await()
            items = emptyList()
        }

        override suspend fun resolve(key: String, origin: String?): RssReadRecord? {
            resolves++
            if (uncooperative) withContext(NonCancellable) { resolveGate?.await() }
            else resolveGate?.await()
            return latest?.copy()
        }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }

    private fun show(
        repo: Fake,
        read: (RssReadRecord) -> Unit = {},
        browser: (String) -> Unit = {},
        close: () -> Unit = {},
        owner: Owner? = null,
    ) {
        compose.runOnIdle { model = RssReadRecordViewModel(repo, SavedStateHandle(), "origin") }
        compose.setContent {
            LegadoComposeTheme {
                if (owner == null) RssReadRecordRoute(model, { true }, read, browser, close, {})
                else
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        RssReadRecordRoute(model, { true }, read, browser, close, {})
                    }
            }
        }
    }

    private fun loaded() {
        compose.waitUntil(5000) { !model.state.value.loading }
    }

    @After
    fun cleanup() {
        if (::model.isInitialized) compose.runOnIdle { model.stop() }
    }

    @Test
    fun titleClickReReadsCurrentArticleTypeAndPositionThenCloses() {
        val records = mutableListOf<RssReadRecord>()
        var closes = 0
        show(Fake(), { records += it }, close = { closes++ })
        loaded()
        compose.onNodeWithTag("rss-history-read-key").performClick()
        compose.waitUntil { records.size == 1 && closes == 1 }
        assertEquals("Latest", records.single().title)
        assertEquals(99, records.single().durPos)
        assertEquals(2, records.single().type)
    }

    @Test
    fun urlLinkOpensBrowserAndKeepsReadingListOpen() {
        val links = mutableListOf<String>()
        var closes = 0
        show(Fake(), browser = { links += it }, close = { closes++ })
        loaded()
        compose.onNodeWithTag("rss-history-url-key").performTouchInput {
            click(Offset(20f, center.y))
        }
        compose.waitUntil { links.size == 1 }
        assertEquals("https://article.example.com", links.single())
        assertEquals(0, closes)
        compose.onNodeWithTag("rss-history-read-key").assertIsDisplayed()
    }

    @Test
    fun clearUsesFreshCountCancelDoesNotDeleteAndConfirmRefreshesList() {
        val repo = Fake()
        show(repo)
        loaded()
        compose.onNodeWithTag("rss-history-clear").performClick()
        compose.waitUntil { model.state.value.clearCount != null }
        compose.onNodeWithTag("rss-history-clear-count").assertTextContains("4")
        compose.onNodeWithTag("rss-history-clear-cancel").performClick()
        assertEquals(0, repo.deletes)
        compose.runOnIdle { repo.counts = 2 }
        compose.onNodeWithTag("rss-history-clear").performClick()
        compose.waitUntil { model.state.value.clearCount == 2 }
        compose.onNodeWithTag("rss-history-clear-count").assertTextContains("2")
        compose.onNodeWithTag("rss-history-clear-confirm").performClick()
        compose.waitUntil { repo.deletes == 1 && !model.state.value.busy }
        compose.onNodeWithTag("rss-history-read-key").assertDoesNotExist()
        assertFalse(model.state.value.finished)
    }

    @Test
    fun activeClearDisablesDuplicateConfirmationAndCannotCloseHalfwayThroughDeletion() {
        val repo = Fake().apply { clearGate = CompletableDeferred() }
        show(repo)
        loaded()
        compose.onNodeWithTag("rss-history-clear").performClick()
        compose.waitUntil { model.state.value.clearCount != null }
        compose.onNodeWithTag("rss-history-clear-confirm").performClick()
        compose.onNodeWithTag("rss-history-clear-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("rss-history-clear-cancel").assertIsNotEnabled()
        compose.runOnIdle {
            model.cancel()
            assertFalse(model.state.value.finished)
            assertEquals(1, repo.deletes)
            repo.clearGate!!.complete(Unit)
        }
        compose.waitUntil { !model.state.value.busy }
        assertEquals(1, repo.deletes)
    }

    @Test
    fun pausedReadWaitsForResumeAndUsesMetadataUpdatedWhilePaused() {
        val owner = Owner()
        val repo = Fake()
        val records = mutableListOf<RssReadRecord>()
        var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        show(repo, { records += it }, close = { closes++ }, owner = owner)
        loaded()
        compose.onNodeWithTag("rss-history-read-key").performClick()
        compose.waitForIdle()
        assertTrue(records.isEmpty())
        compose.runOnIdle {
            repo.latest = repo.latest!!.copy(durPos = 120, type = 1)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { records.size == 1 && closes == 1 }
        assertEquals(120, records.single().durPos)
        assertEquals(1, records.single().type)
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        assertEquals(1, records.size)
    }

    @Test
    fun pendingFailureRetryAndCloseNeverDeliverArticle() {
        val repo =
            Fake().apply {
                gate = CompletableDeferred()
                fail = true
            }
        var opens = 0
        var closes = 0
        show(repo, { opens++ }, close = { closes++ })
        compose.onNodeWithTag("rss-history-clear").assertIsNotEnabled()
        compose.runOnIdle { repo.gate!!.complete(Unit) }
        loaded()
        compose.onNodeWithTag("rss-history-error").assertTextEquals("failed")
        compose.runOnIdle { repo.fail = false }
        compose.onNodeWithTag("rss-history-retry").performClick()
        loaded()
        compose.onNodeWithTag("rss-history-close").performClick()
        compose.waitUntil { closes == 1 }
        assertEquals(0, opens)
        assertEquals(0, repo.deletes)
    }

    @Test
    fun horizontalTitleDragDoesNotOpenArticleAndExplicitTitleClickStillWorks() {
        val repo =
            Fake().apply { items = items.map { it.copy(title = "Long article title ".repeat(50)) } }
        var opens = 0
        show(repo, { opens++ })
        loaded()
        compose.onNodeWithTag("rss-history-read-key").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(0, opens)
        compose.onNodeWithTag("rss-history-read-key").performClick()
        compose.waitUntil { opens == 1 }
    }

    @Test
    fun autoLinkedProtocolLessWebAddressPreservesBrowserPrefixBehavior() {
        val repo =
            Fake().apply {
                items = items.map { it.copy(record = "www.example.com/path") }
                latest = latest!!.copy(record = "www.example.com/path")
            }
        val links = mutableListOf<String>()
        show(repo, browser = { links += it })
        loaded()
        compose.onNodeWithTag("rss-history-url-key").performTouchInput {
            click(Offset(20f, center.y))
        }
        compose.waitUntil { links.isNotEmpty() }
        assertEquals(listOf("http://www.example.com/path"), links)
        assertFalse(model.state.value.finished)
    }

    @Test
    fun lateResolutionFromCanceledLifecycleCannotDuplicateTheFreshResumedRead() {
        val owner = Owner()
        val repo =
            Fake().apply {
                resolveGate = CompletableDeferred()
                uncooperative = true
            }
        val records = mutableListOf<RssReadRecord>()
        var closes = 0
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        show(repo, { records += it }, close = { closes++ }, owner = owner)
        loaded()
        try {
            compose.onNodeWithTag("rss-history-read-key").performClick()
            compose.waitUntil { repo.resolves == 1 }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.waitForIdle()
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil { repo.resolves == 2 }
            compose.runOnIdle {
                repo.latest = repo.latest!!.copy(durPos = 333)
                repo.resolveGate!!.complete(Unit)
            }
            compose.waitUntil { records.size == 1 && closes == 1 }
            assertEquals(333, records.single().durPos)
            compose.waitForIdle()
            assertEquals(1, records.size)
        } finally {
            compose.runOnIdle { repo.resolveGate!!.complete(Unit) }
        }
    }
}
