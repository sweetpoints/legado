package io.legado.app.data.repository

import androidx.media3.common.MediaItem
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class ReviewDetailRepositoryTest {
    private val key = ReviewDetailKey(-1, 4, "captured-data", "https://book", "https://source", 123)
    private fun item(id: String? = null, content: String = "comment", replies: List<ReviewComment> = emptyList(), count: Int? = null) =
        ReviewComment(id, "avatar", "name", "target", listOf("badge"), content, "image", "audio", "time", 2, count, replies)
    private fun exercise(action: suspend (Fake, ReviewDetailRepository) -> Unit) {
        val caller = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val fake = Fake(); runBlocking { action(fake, DefaultReviewDetailRepository(fake, dispatcher)) }
            assertTrue(fake.threads.isNotEmpty()); assertTrue(fake.threads.all { it !== caller })
        }
    }
    @Test fun capturedParagraphChapterAndNextUrlReachIoRequestUnchanged() = exercise { fake, repo ->
        val result = repo.detail(key, 3, "https://next")
        assertNotNull(result); assertEquals(Triple(key, 3, "https://next"), fake.detailRequest)
        assertEquals(4, fake.context.chapter.index); assertEquals("captured-data", fake.detailRequest!!.first.paragraphData)
    }
    @Test fun mismatchedSourceOrBookCannotRequestAnyDetailOrReplies() = exercise { fake, repo ->
        fake.validContext = false
        assertNull(repo.detail(key, 1, null)); assertNull(repo.replies(key, "parent", 1))
        assertNull(fake.detailRequest); assertNull(fake.replyRequest)
    }
    @Test fun sourceChangeWhileAwaitingNetworkRejectsLateResult() = exercise { fake, repo ->
        fake.changeAfterRequest = true
        assertNull(repo.detail(key, 1, null)); assertNotNull(fake.detailRequest)
        fake.current = true; assertNull(repo.replies(key, "parent", 2)); assertEquals(Triple(key, "parent", 2), fake.replyRequest)
    }
    @Test fun requestCancellationPropagatesAndNeverCreatesCache() = exercise { fake, repo ->
        fake.cancel = true
        assertTrue(runCatching { repo.detail(key, 1, null) }.exceptionOrNull() is CancellationException)
        assertNull(fake.cache)
    }
    @Test fun restoredSnapshotRequiresFullCapturedKeyAndCurrentSource() = exercise { fake, repo ->
        val value = ReviewDetailSnapshot(key, listOf(item("one")), page = 2, nextPageUrl = "next", hasReplies = true,
            replyPages = mapOf("m|one" to 3), exhausted = setOf("m|one"), expanded = setOf("m|one"))
        repo.stage("session", value); assertEquals(value, repo.restore("session", key))
        assertNull(repo.restore("session", key.copy(chapterIndex = 5)))
        fake.current = false; assertNull(repo.restore("session", key))
    }
    @Test fun replyRequestUsesCapturedReviewIdAndIndependentPage() = exercise { fake, repo ->
        val result = repo.replies(key, "review-id", 7)
        assertEquals(7, result!!.page); assertEquals(Triple(key, "review-id", 7), fake.replyRequest)
    }
    @Test fun sameMainIdKeepsOriginalBodyAndMergesNewReplyMetadata() {
        val old = item("main", "original", listOf(item("r1")), 1)
        val changed = item("main", "repeated changed body", listOf(item("r1"), item("r2")), 3)
        val result = mergeReviewDetails(listOf(old), listOf(changed))
        assertEquals(1, result.changes); assertEquals(1, result.items.size)
        assertEquals("original", result.items.single().content); assertEquals(3, result.items.single().replyCount)
        assertEquals(listOf("r1", "r2"), result.items.single().replies.map { it.id })
    }
    @Test fun repeatedMainPageWithNoNewRepliesReportsNoProgress() {
        val first = mergeReviewDetails(emptyList(), listOf(item("a"), item("a"), item("b")))
        assertEquals(2, first.changes)
        val repeated = mergeReviewDetails(first.items, listOf(item("a"), item("b")))
        assertEquals(0, repeated.changes); assertEquals(first.items, repeated.items)
    }
    @Test fun blankIdsUseFullFallbackIdentityIncludingMediaAndReplyTarget() {
        val first = item(content = "one"); val other = item(content = "two")
        assertNotEquals(reviewCommentKey(first, false), reviewCommentKey(other, false))
        assertNotEquals(reviewCommentKey(first, false), reviewCommentKey(first, true))
        assertNotEquals(reviewCommentKey(first, true), reviewCommentKey(first.copy(audioUrl = "different"), true))
        assertNotEquals(reviewCommentKey(first, true), reviewCommentKey(first.copy(replyToName = "different"), true))
    }
    @Test fun embeddedAndFetchedRepliesAreDeduplicatedWithoutMutatingInputs() {
        val incoming = listOf(item("r1"), item("r1"), item("r2"))
        val result = mergeReviewDetails(emptyList(), listOf(item("m", replies = incoming)))
        assertEquals(2, result.items.single().replies.size); assertEquals(3, incoming.size)
        assertEquals(listOf("r1", "r2", "r3"), mergeReviewReplies(result.items.single().replies, listOf(item("r2"), item("r3"))).map { it.id })
    }
    private inner class Fake : ReviewDetailStore {
        val context = ReviewDetailContext(BookSource("https://source", "source"), Book(bookUrl = "https://book"),
            BookChapter(bookUrl = "https://book", title = "Captured chapter", url = "https://chapter", index = 4))
        val threads = mutableListOf<Thread>(); var validContext = true; var current = true; var changeAfterRequest = false; var cancel = false
        var cache: ReviewDetailSnapshot? = null; var detailRequest: Triple<ReviewDetailKey, Int, String?>? = null
        var replyRequest: Triple<ReviewDetailKey, String, Int>? = null
        private fun called() { threads += Thread.currentThread() }
        override suspend fun context(key: ReviewDetailKey): ReviewDetailContext? { called(); return context.takeIf { validContext } }
        override suspend fun isCurrent(key: ReviewDetailKey): Boolean { called(); return current }
        override suspend fun detail(context: ReviewDetailContext, key: ReviewDetailKey, page: Int, nextUrl: String?): ReviewDetailPage? {
            called(); detailRequest = Triple(key, page, nextUrl); if (cancel) throw CancellationException("cancel")
            if (changeAfterRequest) current = false
            return ReviewDetailPage(listOf(item("main")), "next", true, true)
        }
        override suspend fun replies(context: ReviewDetailContext, key: ReviewDetailKey, reviewId: String, page: Int): ReviewReplyPage? {
            called(); replyRequest = Triple(key, reviewId, page); if (changeAfterRequest) current = false
            return ReviewReplyPage(listOf(item("reply")), page)
        }
        override suspend fun mediaItem(context: ReviewDetailContext, url: String): MediaItem = error("unused")
        override suspend fun restore(session: String): ReviewDetailSnapshot? { called(); return cache }
        override suspend fun stage(session: String, snapshot: ReviewDetailSnapshot) { called(); cache = snapshot }
    }
}
