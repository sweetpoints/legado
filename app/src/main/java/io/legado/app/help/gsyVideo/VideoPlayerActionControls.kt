package io.legado.app.help.gsyVideo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

internal data class VideoPlayerActionControlsState(
    val episodeControlsVisible: Boolean = false,
    val danmakuVisible: Boolean = false,
    val danmakuEnabled: Boolean = false,
    val selectedSpeed: Float? = null,
)

@Composable
internal fun VideoPlayerActionControls(
    state: VideoPlayerActionControlsState,
    onNext: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.episodeControlsVisible) {
            IconButton(onClick = onNext, modifier = Modifier.testTag("video-next-episode")) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.next_chapter),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.danmakuVisible) {
            TextButton(
                onClick = onToggleDanmaku,
                modifier = Modifier.testTag("video-toggle-danmaku"),
            ) {
                Text(
                    text =
                        stringResource(
                            if (state.danmakuEnabled) R.string.video_danmaku_disable
                            else R.string.video_danmaku_enable
                        ),
                    color = Color.White,
                )
            }
        }
        if (state.episodeControlsVisible) {
            TextButton(
                onClick = onOpenEpisodes,
                modifier = Modifier.testTag("video-open-episodes"),
            ) {
                Text(stringResource(R.string.chapter_list), color = Color.White)
            }
            TextButton(onClick = onOpenSpeed, modifier = Modifier.testTag("video-open-speed")) {
                val speed = state.selectedSpeed
                Text(
                    text =
                        speed?.let { stringResource(R.string.video_speed_multiple, it) }
                            ?: stringResource(R.string.video_playback_speed),
                    color = Color.White,
                )
            }
        }
    }
}
