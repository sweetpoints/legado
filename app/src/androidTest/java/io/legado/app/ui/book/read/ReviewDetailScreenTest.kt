package io.legado.app.ui.book.read

import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.repository.*
import io.legado.app.ui.theme.LegadoComposeTheme
import io.legado.app.ui.widget.dialog.photo.PhotoImage
import io.legado.app.ui.widget.dialog.photo.PhotoImageLoader
import io.legado.app.ui.widget.dialog.photo.PhotoRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReviewDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val defaultLoader = PhotoImageLoader { PhotoImage.Static(Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)) }
    private val key = ReviewDetailKey(1, 0, "token", "book", "captured-source", 1)
    private fun comment(id: String, replies: List<ReviewComment> = emptyList(), count: Int? = null,
        media: Boolean = false, likes: Int? = null) = ReviewComment(id, if (media) "https://avatar-$id" else null,
        "Name $id", "Target", if (media) listOf("Badge $id", "https://badge-$id") else listOf("Badge $id"),
        "Body $id", if (media) "https://image-$id" else null, if (media) "https://audio-$id" else null, "Time", likes, count, replies)
    @Test fun sourceAwareAvatarBadgeAndMediaRequestsIncludeReplyBadgesAndBodyTarget() {
        val requests = mutableListOf<PhotoRequest>(); val loader = PhotoImageLoader { request ->
            requests += request; PhotoImage.Static(Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888))
        }
        val state = ReviewDetailState(ReviewDetailSnapshot(key, listOf(comment("main", listOf(comment("reply", media = true)), media = true))), loading = false)
        compose.setContent { Content(state, loader = loader) }
        compose.waitUntil { requests.size == 6 }
        assertEquals(setOf("https://avatar-main", "https://badge-main", "https://image-main", "https://avatar-reply", "https://badge-reply", "https://image-reply"), requests.map { it.src }.toSet())
        assertTrue(requests.all { it.sourceOrigin == "captured-source" })
        compose.onNodeWithText("Badge reply").assertExists()
        compose.onNodeWithText("Name reply").assertExists()
        compose.onNodeWithText("回复 Target：Body reply").assertExists()
    }
    @Test fun mediaAndAudioClicksUseStableRowsWithoutRequestingReplies() {
        val state = ReviewDetailState(ReviewDetailSnapshot(key, listOf(comment("main", media = true))), loading = false)
        val row = state.rows.single().key; val photos = mutableListOf<String>(); val audio = mutableListOf<String>(); var replies = 0
        compose.setContent { Content(state, photo = { photos += it }, audioClick = { audio += it }, replies = { replies++ }) }
        compose.onNodeWithTag("review-detail-image-$row").performClick()
        compose.onNodeWithTag("review-detail-audio-$row").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(row), photos); assertEquals(listOf(row), audio); assertEquals(0, replies) }
    }
    @Test fun moreReplyShowsCountAndLoadingRowBlocksRepeatedRequest() {
        val snapshot = ReviewDetailSnapshot(key, listOf(comment("main", count = 5)), hasReplies = true)
        var state by mutableStateOf(ReviewDetailState(snapshot, loading = false)); var calls = 0
        compose.setContent { Content(state, replies = { calls++; state = state.copy(loadingReplies = state.loadingReplies + it) }) }
        compose.onNodeWithTag("review-detail-more-m|main").assertTextContains(context.getString(R.string.review_more_replies, 5)).performClick()
        compose.onNodeWithTag("review-detail-more-m|main").assertIsNotEnabled().assertTextContains(context.getString(R.string.loading))
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun replyLikeCountsAppearOnlyWhenPositiveAndMainPlaceholderIsPreserved() {
        val state = ReviewDetailState(ReviewDetailSnapshot(key, listOf(comment("main", listOf(comment("zero", likes = 0), comment("positive", likes = 2))))), loading = false)
        val zero = state.rows.first { it.comment?.id == "zero" }.key; val positive = state.rows.first { it.comment?.id == "positive" }.key
        compose.setContent { Content(state) }
        compose.onNodeWithTag("review-detail-likes-$zero").assertDoesNotExist()
        compose.onNodeWithTag("review-detail-likes-$positive").assertTextEquals("2")
        assertTrue(reviewHasLikes(state.rows.first().comment!!, false))
    }
    @Test fun audioLabelsReflectPreparingPlayingAndPauseWithoutPlayingOnRowClick() {
        val state = ReviewDetailState(ReviewDetailSnapshot(key, listOf(comment("main", media = true))), loading = false)
        val row = state.rows.single().key; var audio by mutableStateOf(ReviewAudioState("https://audio-main", preparing = true))
        compose.setContent { Content(state, audio = audio) }
        compose.onNodeWithTag("review-detail-audio-$row").assertIsNotEnabled().assertTextContains(context.getString(R.string.loading))
        compose.runOnIdle { audio = audio.copy(preparing = false, playing = true) }
        compose.onNodeWithTag("review-detail-audio-$row").assertIsEnabled().assertTextContains(context.getString(R.string.review_pause_audio))
        compose.runOnIdle { audio = audio.copy(playing = false) }
        compose.onNodeWithTag("review-detail-audio-$row").assertTextContains(context.getString(R.string.review_play_audio))
    }
    @Test fun actualResizeDragDoesNotToggleOnReleaseAndTapHasAccessibleDescription() {
        val state = ReviewDetailState(ReviewDetailSnapshot(key), loading = false)
        var ratio = .68f; var toggles = 0; var delta = 0f
        compose.setContent { Content(state, heightToggle = { toggles++; ratio = .92f }, heightDrag = { delta += it; ratio -= it / 1000f }) }
        compose.onNodeWithTag("review-detail-resize").assertContentDescriptionEquals(context.getString(R.string.review_resize_handle))
            .performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -80f)); up() }
        compose.runOnIdle { assertEquals(0, toggles); assertTrue(delta < 0); assertTrue(ratio > .68f && ratio < .92f) }
        compose.onNodeWithTag("review-detail-resize").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
    @Test fun closeUsesLocalizedDescriptionAndRemainsReachableInShortWindow() {
        var closes = 0
        compose.setContent { Content(ReviewDetailState(ReviewDetailSnapshot(key), loading = false), close = { closes++ }, height = 220) }
        compose.onNodeWithTag("review-detail-close").assertContentDescriptionEquals(context.getString(R.string.close)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }
    @Test fun failedInitialLoadShowsRetryAndEmptyContentUsesLocalizedLabel() {
        var state by mutableStateOf(ReviewDetailState(ReviewDetailSnapshot(key), loading = false, error = "network")); var retry = 0
        compose.setContent { Content(state, retry = { retry++ }) }
        compose.onNodeWithTag("review-detail-empty").assertTextEquals("network")
        compose.onNodeWithTag("review-detail-retry").performClick(); compose.runOnIdle { assertEquals(1, retry); state = state.copy(error = null) }
        compose.onNodeWithTag("review-detail-empty").assertTextEquals(context.getString(R.string.content_empty))
    }
    @Composable private fun Content(state: ReviewDetailState, loader: PhotoImageLoader = defaultLoader,
        audio: ReviewAudioState = ReviewAudioState(), replies: (String) -> Unit = {}, photo: (String) -> Unit = {},
        audioClick: (String) -> Unit = {}, close: () -> Unit = {}, heightToggle: () -> Unit = {}, heightDrag: (Float) -> Unit = {},
        retry: () -> Unit = {}, height: Int = 700) {
        LegadoComposeTheme { ReviewDetailScreen(state, 10, "captured-source", rememberLazyListState(), audio, loader, remember { ReviewImageDimensions() },
            replies, photo, audioClick, close, heightToggle, heightDrag, retry, Modifier.height(height.dp)) }
    }
}
