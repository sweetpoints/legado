package io.legado.app.data.repository

import androidx.media3.common.MediaItem
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.ReviewRuleParser.DetailItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

internal data class ReviewDetailKey(val paragraphNum: Int, val chapterIndex: Int, val paragraphData: String,
    val bookUrl: String, val sourceKey: String, val ruleHash: Int)
internal data class ReviewDetailContext(val source: BookSource, val book: Book, val chapter: BookChapter)
internal data class ReviewComment(val id: String?, val avatar: String?, val name: String?, val replyToName: String?,
    val badges: List<String>, val content: String?, val imageUrl: String?, val audioUrl: String?, val time: String?,
    val likeCount: Int?, val replyCount: Int?, val replies: List<ReviewComment>)
internal fun DetailItem.toReviewComment(): ReviewComment = ReviewComment(id, avatar, name, replyToName,
    badges.toList(), content, imageUrl, audioUrl, time, likeCount, replyCount, replies.map { it.toReviewComment() })
internal data class ReviewDetailPage(val items: List<ReviewComment>, val nextPageUrl: String?,
    val hasNextPageRule: Boolean, val hasReplyUrl: Boolean)
internal data class ReviewReplyPage(val replies: List<ReviewComment>, val page: Int)
internal data class ReviewDetailSnapshot(val key: ReviewDetailKey, val items: List<ReviewComment> = emptyList(),
    val page: Int = 0, val nextPageUrl: String? = null, val hasMore: Boolean = true, val hasReplies: Boolean = false,
    val replyPages: Map<String, Int> = emptyMap(), val exhausted: Set<String> = emptySet(), val expanded: Set<String> = emptySet())
internal interface ReviewDetailStore {
    suspend fun context(key: ReviewDetailKey): ReviewDetailContext?
    suspend fun isCurrent(key: ReviewDetailKey): Boolean
    suspend fun detail(context: ReviewDetailContext, key: ReviewDetailKey, page: Int, nextUrl: String?): ReviewDetailPage?
    suspend fun replies(context: ReviewDetailContext, key: ReviewDetailKey, reviewId: String, page: Int): ReviewReplyPage?
    suspend fun mediaItem(context: ReviewDetailContext, url: String): MediaItem
    suspend fun restore(session: String): ReviewDetailSnapshot?
    suspend fun stage(session: String, snapshot: ReviewDetailSnapshot)
}
internal interface ReviewDetailRepository {
    suspend fun detail(key: ReviewDetailKey, page: Int, nextUrl: String?): ReviewDetailPage?
    suspend fun replies(key: ReviewDetailKey, reviewId: String, page: Int): ReviewReplyPage?
    suspend fun mediaItem(key: ReviewDetailKey, url: String): MediaItem?
    suspend fun restore(session: String, key: ReviewDetailKey): ReviewDetailSnapshot?
    suspend fun stage(session: String, snapshot: ReviewDetailSnapshot)
}
internal class DefaultReviewDetailRepository(private val store: ReviewDetailStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ReviewDetailRepository {
    override suspend fun detail(key: ReviewDetailKey, page: Int, nextUrl: String?): ReviewDetailPage? = withContext(dispatcher) {
        require(page > 0)
        val context = store.context(key) ?: return@withContext null
        val result = store.detail(context, key, page, nextUrl)
        result.takeIf { store.isCurrent(key) }
    }
    override suspend fun replies(key: ReviewDetailKey, reviewId: String, page: Int): ReviewReplyPage? = withContext(dispatcher) {
        require(reviewId.isNotBlank() && page > 0)
        val context = store.context(key) ?: return@withContext null
        val result = store.replies(context, key, reviewId, page)
        result.takeIf { store.isCurrent(key) }
    }
    override suspend fun mediaItem(key: ReviewDetailKey, url: String): MediaItem? = withContext(dispatcher) {
        require(url.isNotBlank())
        val context = store.context(key) ?: return@withContext null
        val result = store.mediaItem(context, url)
        result.takeIf { store.isCurrent(key) }
    }
    override suspend fun restore(session: String, key: ReviewDetailKey): ReviewDetailSnapshot? = withContext(dispatcher) {
        if (!store.isCurrent(key)) return@withContext null
        store.restore(session)?.takeIf { it.key == key }
    }
    override suspend fun stage(session: String, snapshot: ReviewDetailSnapshot) = withContext(dispatcher) {
        store.stage(session, snapshot)
    }
}
internal fun reviewCommentKey(item: ReviewComment, isReply: Boolean): String {
    val prefix = if (isReply) "r" else "m"
    val id = item.id?.trim().orEmpty()
    if (id.isNotEmpty()) return "$prefix|$id"
    return listOf(prefix, item.name.orEmpty(), item.replyToName.orEmpty(), item.content.orEmpty(), item.time.orEmpty(),
        item.avatar.orEmpty(), item.imageUrl.orEmpty(), item.audioUrl.orEmpty()).joinToString("|")
}
internal fun mergeReviewReplies(old: List<ReviewComment>, incoming: List<ReviewComment>): List<ReviewComment> {
    val seen = hashSetOf<String>()
    return (old + incoming).filter { seen.add(reviewCommentKey(it, true)) }
}
internal data class ReviewDetailMerge(val items: List<ReviewComment>, val changes: Int)
internal fun mergeReviewDetails(old: List<ReviewComment>, incoming: List<ReviewComment>): ReviewDetailMerge {
    val items = old.toMutableList(); val indices = items.mapIndexed { index, item -> reviewCommentKey(item, false) to index }.toMap().toMutableMap()
    var changes = 0
    incoming.forEach { value ->
        val key = reviewCommentKey(value, false); val normalized = value.copy(replies = mergeReviewReplies(emptyList(), value.replies))
        val index = indices[key]
        if (index == null) { indices[key] = items.size; items += normalized; changes++ }
        else {
            val previous = items[index]
            val replies = mergeReviewReplies(previous.replies, normalized.replies)
            val count = max(previous.replyCount ?: 0, normalized.replyCount ?: 0).takeIf { it > 0 }
            if (replies.size != previous.replies.size || count != previous.replyCount) {
                items[index] = previous.copy(replies = replies, replyCount = count); changes++
            }
        }
    }
    return ReviewDetailMerge(items.toList(), changes)
}
