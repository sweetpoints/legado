package io.legado.app.ui.book.read

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.repository.ReviewComment
import io.legado.app.ui.widget.dialog.photo.PhotoImageLoader
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl

internal data class ReviewAudioState(val url: String? = null, val preparing: Boolean = false, val playing: Boolean = false)
internal fun reviewHasLikes(comment: ReviewComment, reply: Boolean): Boolean = !reply || (comment.likeCount ?: 0) > 0
internal fun reviewReplyBody(comment: ReviewComment, reply: Boolean): String {
    val content = if (reply) comment.content.orEmpty().trim() else comment.content.orEmpty()
    val target = comment.replyToName.orEmpty().trim()
    return if (reply && content.isNotEmpty() && target.isNotEmpty()) "回复 $target：$content" else content
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ReviewDetailScreen(state: ReviewDetailState, totalCount: Int, sourceKey: String,
    listState: LazyListState, audio: ReviewAudioState, imageLoader: PhotoImageLoader, dimensions: ReviewImageDimensions,
    onReplies: (String) -> Unit, onPhoto: (String) -> Unit, onAudio: (String) -> Unit, onClose: () -> Unit,
    onHeightToggle: () -> Unit, onHeightDrag: (Float) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val drag by rememberUpdatedState(onHeightDrag)
    val resizeLabel = stringResource(R.string.review_resize_handle)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(48.dp).testTag("review-detail-resize")
                .semantics { contentDescription = resizeLabel }
                .clickable(enabled = !state.finished, role = Role.Button, onClick = onHeightToggle)
                .pointerInput(state.finished) {
                    if (!state.finished) detectVerticalDragGestures { change, delta -> change.consume(); drag(delta) }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose, modifier = Modifier.testTag("review-detail-close")) {
                    Icon(painterResource(R.drawable.ic_baseline_close), stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (totalCount > 0) Text(stringResource(R.string.review_total_count, totalCount),
                    Modifier.padding(end = 14.dp).testTag("review-detail-count"), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("review-detail-progress"))
            val rows = remember(state.snapshot, state.loadingReplies) { state.rows }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("review-detail-list"), state = listState) {
                if (!state.loading && rows.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(state.error ?: stringResource(R.string.content_empty), Modifier.testTag("review-detail-empty"))
                        if (state.error != null) TextButton(onRetry, Modifier.testTag("review-detail-retry")) { Text(stringResource(R.string.retry)) }
                    }
                }
                items(rows.size, key = { rows[it].key }) { index ->
                    val row = rows[index]
                    val comment = row.comment
                    if (comment == null) TextButton({ onReplies(row.parent) }, enabled = !row.loading && !state.finished,
                        modifier = Modifier.fillMaxWidth().padding(start = 44.dp).testTag("review-detail-more-${row.parent}")) {
                        Text(if (row.loading) stringResource(R.string.loading) else stringResource(R.string.review_more_replies, row.more))
                    } else Row(Modifier.fillMaxWidth().padding(start = if (row.reply) 44.dp else 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                        .testTag("review-detail-row-${row.key}"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!comment.avatar.isNullOrBlank()) ReviewDetailImage(comment.avatar, sourceKey, imageLoader, dimensions,
                            Modifier.size(if (row.reply) 28.dp else 36.dp).clip(CircleShape))
                        Column(Modifier.weight(1f)) {
                            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!comment.name.isNullOrBlank()) Text(comment.name, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("review-detail-name-${row.key}"))
                                comment.badges.forEach { badge ->
                                    if (badge.isAbsUrl() || badge.isDataUrl()) ReviewDetailImage(badge, sourceKey, imageLoader, dimensions, badge = true)
                                    else Text(badge, Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val body = reviewReplyBody(comment, row.reply)
                            if (body.isNotBlank()) {
                                val prefix = if (row.reply && comment.replyToName?.trim()?.isNotEmpty() == true && comment.content?.trim()?.isNotEmpty() == true)
                                    "回复 ${comment.replyToName.trim()}：" else ""
                                val prefixColor = MaterialTheme.colorScheme.onSurfaceVariant
                                val text = buildAnnotatedString { append(body); if (prefix.isNotEmpty()) addStyle(SpanStyle(color = prefixColor), 0, prefix.length) }
                                Text(text, Modifier.padding(top = if (row.reply) 0.dp else 4.dp).testTag("review-detail-body-${row.key}"),
                                    fontSize = if (row.reply) 14.sp else 16.sp, color = colorResource(R.color.reviewContentText))
                            }
                            if (!comment.imageUrl.isNullOrBlank()) ReviewDetailImage(comment.imageUrl, sourceKey, imageLoader, dimensions,
                                Modifier.padding(top = 6.dp).testTag("review-detail-image-${row.key}")
                                    .clickable { onPhoto(row.key) }, media = true, description = stringResource(R.string.preview_image_by_click))
                            if (!comment.audioUrl.isNullOrBlank()) {
                                val pending = state.effects.any { it.action == ReviewDetailAction.Audio && it.rowKey == row.key }
                                val playing = audio.url == comment.audioUrl && audio.playing
                                val preparing = pending || audio.url == comment.audioUrl && audio.preparing
                                TextButton({ onAudio(row.key) }, enabled = !preparing && !state.finished,
                                    modifier = Modifier.testTag("review-detail-audio-${row.key}")) {
                                    Icon(painterResource(if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp), null)
                                    Text(stringResource(if (preparing) R.string.loading else if (playing) R.string.review_pause_audio else R.string.review_play_audio))
                                }
                            }
                            if (!comment.time.isNullOrBlank()) Text(comment.time, Modifier.padding(top = 7.dp), fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (reviewHasLikes(comment, row.reply)) Column(Modifier.width(44.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(painterResource(R.drawable.ic_review_like), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            if ((comment.likeCount ?: 0) > 0) Text(comment.likeCount.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("review-detail-likes-${row.key}"))
                        }
                    }
                }
            }
        }
    }
}
