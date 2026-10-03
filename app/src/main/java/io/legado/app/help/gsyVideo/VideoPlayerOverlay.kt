package io.legado.app.help.gsyVideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

internal data class VideoPlayerOverlayState(
    val title: String = "",
    val fullscreen: Boolean = false,
    val controlsVisible: Boolean = true,
    val showProgressWhenHidden: Boolean = true,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val locked: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val progress: Float = 0f,
    val bufferedProgress: Float = 0f,
    val tip: String? = null,
)

@Composable
internal fun VideoPlayerOverlay(
    state: VideoPlayerOverlayState,
    actions: VideoPlayerActionControlsState,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleLock: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekStarted: () -> Unit,
    onSeekFinished: (Float) -> Unit,
    onNext: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state.fullscreen && state.controlsVisible && state.locked) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier.align(Alignment.CenterEnd).testTag("video-lock-toggle"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock_outline),
                    contentDescription = stringResource(R.string.video_toggle_lock),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        if (state.controlsVisible && !state.locked) {
            if (state.fullscreen) {
                Column(
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("video-fullscreen-back"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.back),
                                tint = Color.White,
                            )
                        }
                        Text(
                            text = state.title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        IconButton(
                            onClick = onToggleLock,
                            modifier = Modifier.testTag("video-lock-toggle"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock_outline),
                                contentDescription = stringResource(R.string.video_toggle_lock),
                                tint = Color.White,
                            )
                        }
                        IconButton(
                            onClick = onToggleFullscreen,
                            modifier = Modifier.testTag("video-fullscreen-toggle"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_fullscreen),
                                contentDescription =
                                    stringResource(R.string.video_toggle_fullscreen),
                                tint = Color.White,
                            )
                        }
                    }
                }
            }

            if (state.buffering) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            } else {
                IconButton(
                    onClick = onTogglePlayback,
                    modifier =
                        Modifier.align(Alignment.Center)
                            .size(64.dp)
                            .testTag("video-playback-toggle"),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                if (state.playing) R.drawable.ic_pause_24dp
                                else R.drawable.ic_play_24dp
                            ),
                        contentDescription =
                            stringResource(if (state.playing) R.string.pause else R.string.start),
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            Column(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(
                            horizontal = if (state.fullscreen) 12.dp else 4.dp,
                            vertical = 4.dp,
                        )
            ) {
                var sliderPosition by
                    remember(state.progress, state.currentPosition) {
                        mutableFloatStateOf(state.progress.coerceIn(0f, 1f))
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatVideoTime(state.currentPosition),
                        color = Color.White,
                        modifier = Modifier.testTag("video-current-time"),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier =
                                Modifier.align(Alignment.Center)
                                    .fillMaxWidth(state.bufferedProgress.coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .background(Color.White.copy(alpha = 0.35f))
                        )
                        Slider(
                            value = sliderPosition,
                            onValueChange = { value ->
                                if (state.duration > 0L) {
                                    if (sliderPosition == state.progress) onSeekStarted()
                                    sliderPosition = value
                                }
                            },
                            onValueChangeFinished = { onSeekFinished(sliderPosition) },
                            enabled = state.duration > 0L,
                            valueRange = 0f..1f,
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.35f),
                                ),
                            modifier = Modifier.fillMaxWidth().testTag("video-seek-slider"),
                        )
                    }
                    Text(
                        text = formatVideoTime(state.duration),
                        color = Color.White,
                        modifier = Modifier.testTag("video-total-time"),
                    )
                }
                VideoPlayerActionControls(
                    state =
                        if (state.fullscreen) actions
                        else actions.copy(episodeControlsVisible = false),
                    onNext = onNext,
                    onToggleDanmaku = onToggleDanmaku,
                    onOpenEpisodes = onOpenEpisodes,
                    onOpenSpeed = onOpenSpeed,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.fullscreen) {
                    IconButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.align(Alignment.End).testTag("video-fullscreen-toggle"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_fullscreen),
                            contentDescription = stringResource(R.string.video_toggle_fullscreen),
                            tint = Color.White,
                        )
                    }
                }
            }
        } else if (state.showProgressWhenHidden) {
            Box(
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.35f))
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                            .background(Color.White)
                )
            }
        }

        state.tip?.let { message ->
            Text(
                text = message,
                color = Color.White,
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .padding(top = if (state.fullscreen) 64.dp else 8.dp)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("video-overlay-tip"),
            )
        }
    }
}

private fun formatVideoTime(position: Long): String {
    val totalSeconds = (position / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = totalSeconds / 60L % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
