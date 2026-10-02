package io.legado.app.ui.book.read

import io.legado.app.data.repository.*
import org.junit.Assert.*
import org.junit.Test

/** Platform image/audio and resumed delivery contracts are exercised by Android tests. */
class ReviewDetailMediaSourceTest {
    private fun comment(id: String, target: String? = null, likes: Int? = null, replies: List<ReviewComment> = emptyList()) =
        ReviewComment(id, "avatar", "Commenter", target, listOf("Text badge", "https://badge"), " Body ", "image", "audio", "time", likes, 3, replies)
    @Test fun replyTargetPrefixesBodyWithoutChangingCommenterOrBadges() {
        val reply = comment("reply", " Target ")
        assertEquals("回复 Target：Body", reviewReplyBody(reply, true))
        assertEquals(" Body ", reviewReplyBody(reply, false))
        assertEquals("Commenter", reply.name)
        assertEquals(listOf("Text badge", "https://badge"), reply.badges)
        assertEquals("Body", reviewReplyBody(comment("blank", "  "), true))
    }
    @Test fun mainLikePlaceholderSurvivesAndRepliesOnlyShowPositiveLikes() {
        assertTrue(reviewHasLikes(comment("main"), false))
        listOf(null, -1, 0).forEach { assertFalse(reviewHasLikes(comment("reply", likes = it), true)) }
        assertTrue(reviewHasLikes(comment("reply", likes = 1), true))
    }
    @Test fun embeddedRepliesRenderBeforeAnyPagedRequestAndMoreCountUsesLoadedReplies() {
        val key = ReviewDetailKey(1, 2, "token", "book", "source", 1)
        val main = comment("main", replies = listOf(comment("embedded")))
        val state = ReviewDetailState(ReviewDetailSnapshot(key, listOf(main), hasReplies = true), loading = false)
        assertEquals(listOf("main", "embedded", null), state.rows.map { it.comment?.id })
        assertTrue(state.rows[1].reply); assertEquals(2, state.rows.last().more)
        assertEquals("m|main", state.rows.last().parent)
        val exhausted = state.copy(snapshot = state.snapshot.copy(exhausted = setOf("m|main")))
        assertEquals(2, exhausted.rows.size)
    }
}
