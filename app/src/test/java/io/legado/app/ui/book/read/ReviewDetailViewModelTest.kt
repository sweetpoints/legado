package io.legado.app.ui.book.read

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import io.legado.app.data.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val key = ReviewDetailKey(-1, 3, "token", "book", "source", 123)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun copy(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    private fun model(repo: Fake, saved: SavedStateHandle = SavedStateHandle()) =
        ReviewDetailViewModel(repo, saved, key)

    @Test
    fun initialPageExposesEmbeddedRepliesAndOneMoreButtonWithoutAnotherRequest() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            assertEquals(1, repo.pages.size)
            assertFalse(model.state.value.loading)
            val rows = model.state.value.rows
            assertEquals(3, rows.size)
            assertEquals("m", rows[0].comment!!.id)
            assertTrue(rows[1].reply)
            assertEquals("r0", rows[1].comment!!.id)
            assertEquals(2, rows[2].more)
        }

    @Test
    fun nextPageUsesReturnedUrlAndStopsOnRepeatedPageWithoutChangingMainBody() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            model.nextPage()
            runCurrent()
            assertEquals(listOf(1 to null, 2 to "next"), repo.pages)
            assertEquals("main body", model.state.value.snapshot.items.first().content)
            assertFalse(model.state.value.snapshot.hasMore)
            model.nextPage()
            runCurrent()
            assertEquals(2, repo.pages.size)
        }

    @Test
    fun replyPagingMergesUniqueRowsAndStopsAtDeclaredCount() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            model.replies("m|m")
            model.replies("m|m")
            runCurrent()
            assertEquals(listOf("m" to 1), repo.replyCalls)
            assertEquals(
                listOf("r0", "r1"),
                model.state.value.snapshot.items.single().replies.map { it.id },
            )
            model.replies("m|m")
            runCurrent()
            assertEquals(listOf("m" to 1, "m" to 2), repo.replyCalls)
            assertTrue("m|m" in model.state.value.snapshot.exhausted)
            model.replies("m|m")
            runCurrent()
            assertEquals(2, repo.replyCalls.size)
            assertTrue(model.state.value.rows.none { it.comment == null })
        }

    @Test
    fun restoredCacheKeepsReplyPagesAndExpandedRowsWithoutRefetchingMainPage() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            runCurrent()
            first.replies("m|m")
            runCurrent()
            first.resize(.5f)
            val restored = model(repo, copy(saved))
            runCurrent()
            assertEquals(1, repo.pages.size)
            assertEquals(1, repo.replyCalls.size)
            assertEquals(1, restored.state.value.snapshot.replyPages["m|m"])
            assertEquals(.92f, restored.state.value.heightRatio)
            assertEquals(4, restored.state.value.rows.size)
        }

    @Test
    fun replyFailureRetainsItsPageForExplicitRetryAndDoesNotMarkExhausted() =
        runTest(dispatcher) {
            val repo = Fake().apply { replyFails = true }
            val model = model(repo)
            runCurrent()
            model.replies("m|m")
            runCurrent()
            assertTrue(model.state.value.loadingReplies.isEmpty())
            assertTrue(model.state.value.snapshot.replyPages.isEmpty())
            assertTrue(model.state.value.snapshot.exhausted.isEmpty())
            assertEquals(ReviewDetailAction.Toast, model.state.value.effects.single().action)
            repo.replyFails = false
            model.replies("m|m")
            runCurrent()
            assertEquals(listOf("m" to 1, "m" to 1), repo.replyCalls)
        }

    @Test
    fun closeCancelsBothMainAndReplyWorkAndCannotDeliverLateEffects() =
        runTest(dispatcher) {
            val repo = Fake()
            val model = model(repo)
            runCurrent()
            repo.mainGate = CompletableDeferred()
            repo.replyGate = CompletableDeferred()
            model.nextPage()
            model.replies("m|m")
            runCurrent()
            model.cancel()
            repo.mainGate!!.complete(Unit)
            repo.replyGate!!.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.finished)
            assertEquals(1, model.state.value.snapshot.page)
            assertTrue(model.state.value.effects.isEmpty())
            assertTrue(model.state.value.loadingReplies.isEmpty())
        }

    @Test
    fun initialFailureExposesRetryAndRetryDoesNotReadBrokenCacheAgain() =
        runTest(dispatcher) {
            val repo = Fake().apply { restoreFails = true }
            val model = model(repo)
            runCurrent()
            assertEquals("cache", model.state.value.error)
            assertFalse(model.state.value.loading)
            repo.restoreFails = false
            model.retry()
            runCurrent()
            assertEquals(1, repo.restores)
            assertNull(model.state.value.error)
            assertEquals(1, model.state.value.snapshot.page)
        }

    @Test
    fun cancelledReplyRestoresSameRequestedPageWhileStaleCompletedStateSkipsIt() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val first = model(repo, saved)
            val store = ViewModelStore().apply { put("first", first) }
            runCurrent()
            repo.replyGate = CompletableDeferred()
            first.replies("m|m")
            runCurrent()
            val pending = copy(saved)
            store.clear()
            runCurrent()
            repo.replyGate = null
            val stalePending = copy(pending)
            val restored = model(repo, pending)
            runCurrent()
            assertEquals(listOf("m" to 1, "m" to 1), repo.replyCalls)
            assertEquals(1, restored.state.value.snapshot.replyPages["m|m"])
            val stale = model(repo, stalePending)
            runCurrent()
            assertEquals(2, repo.replyCalls.size)
            assertEquals(1, stale.state.value.snapshot.replyPages["m|m"])
        }

    @Test
    fun consumedPhotoAndAudioRestoreWithoutEmbeddingDataImagesInSavedState() =
        runTest(dispatcher) {
            val repo = Fake().apply { image = "data:image/png;base64," + "x".repeat(1_100_000) }
            val saved = SavedStateHandle()
            val model = model(repo, saved)
            runCurrent()
            val row = model.state.value.rows.first().key
            model.photo(row)
            model.audio(row)
            model.audio(row)
            assertEquals(2, model.state.value.effects.size)
            assertEquals(repo.image, model.effectUrl(model.state.value.effects.first()))
            val stored = saved.get<String>("effects")!!
            assertTrue(stored.length < 1000)
            assertFalse(stored.contains("data:image"))
            model.state.value.effects.toList().forEach { model.consumeEffect(it.id) }
            val restored = model(repo, copy(saved))
            runCurrent()
            assertTrue(restored.state.value.effects.isEmpty())
        }

    @Test
    fun heightDragIsBoundedAndClickToggleRestoresIndependentHeightDraft() =
        runTest(dispatcher) {
            val repo = Fake()
            val saved = SavedStateHandle()
            val model = model(repo, saved)
            runCurrent()
            model.resize(-100f)
            assertEquals(.35f, model.state.value.heightRatio)
            model.toggleHeight()
            assertEquals(.92f, model.state.value.heightRatio)
            model.toggleHeight()
            assertEquals(.68f, model.state.value.heightRatio)
            model.resize(.1f)
            val restored = model(repo, copy(saved))
            runCurrent()
            assertEquals(model.state.value.heightRatio, restored.state.value.heightRatio)
        }

    @Test
    fun sourceUnavailableProducesEmptyExhaustedPageAndRepliesRuleMessage() =
        runTest(dispatcher) {
            val repo = Fake().apply { unavailable = true }
            val model = model(repo)
            runCurrent()
            assertTrue(model.state.value.rows.isEmpty())
            assertFalse(model.state.value.snapshot.hasMore)
            assertNull(model.state.value.error)
        }

    private fun comment(
        id: String,
        body: String = "body",
        count: Int? = null,
        replies: List<ReviewComment> = emptyList(),
        image: String? = null,
    ) =
        ReviewComment(
            id,
            null,
            "name",
            "reply-target",
            listOf("badge"),
            body,
            image,
            "audio",
            "time",
            1,
            count,
            replies,
        )

    private inner class Fake : ReviewDetailRepository {
        var cache: ReviewDetailSnapshot? = null
        var restores = 0
        var restoreFails = false
        var replyFails = false
        var unavailable = false
        var image: String? = "https://image"
        var mainGate: CompletableDeferred<Unit>? = null
        var replyGate: CompletableDeferred<Unit>? = null
        val pages = mutableListOf<Pair<Int, String?>>()
        val replyCalls = mutableListOf<Pair<String, Int>>()

        override suspend fun detail(
            key: ReviewDetailKey,
            page: Int,
            nextUrl: String?,
        ): ReviewDetailPage? {
            pages += page to nextUrl
            mainGate?.await()
            if (unavailable) return null
            return ReviewDetailPage(
                listOf(comment("m", "main body", 3, listOf(comment("r0")), image)),
                "next",
                true,
                true,
            )
        }

        override suspend fun replies(
            key: ReviewDetailKey,
            reviewId: String,
            page: Int,
        ): ReviewReplyPage? {
            replyCalls += reviewId to page
            replyGate?.await()
            if (replyFails) error("reply")
            return ReviewReplyPage(listOf(comment("r0"), comment("r$page")), page)
        }

        override suspend fun mediaItem(key: ReviewDetailKey, url: String): MediaItem? =
            error("unused")

        override suspend fun restore(session: String, key: ReviewDetailKey): ReviewDetailSnapshot? {
            restores++
            if (restoreFails) error("cache")
            return cache
        }

        override suspend fun stage(session: String, snapshot: ReviewDetailSnapshot) {
            cache = snapshot
        }
    }
}
