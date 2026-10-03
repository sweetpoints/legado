package io.legado.app.help.gsyVideo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R

internal data class FloatingPlayerControlsState(
    val controlsVisible: Boolean = true,
    val playing: Boolean = false,
    val progress: Float = 0f,
)

@Composable
internal fun FloatingPlayerControls(
    state: FloatingPlayerControlsState,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    onPlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (state.controlsVisible) {
            Row(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color.Black.copy(alpha = 0.25f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(36.dp).testTag("floating-video-close"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_close),
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onFullscreen,
                    modifier = Modifier.size(36.dp).testTag("floating-video-fullscreen"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_fullscreen),
                        contentDescription = stringResource(R.string.video_toggle_fullscreen),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            IconButton(
                onClick = onPlayback,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        .size(44.dp)
                        .testTag("floating-video-playback"),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (state.playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
                        ),
                    contentDescription =
                        stringResource(if (state.playing) R.string.pause else R.string.start),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

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
                        .testTag("floating-video-progress")
            )
        }
    }
}
