package io.legado.app.ui.video

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class VideoBookHeaderState(
    val title: String = "",
    val author: String = "",
)

@Composable
internal fun VideoBookHeaderScreen(
    state: VideoBookHeaderState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.title,
                modifier =
                    Modifier.horizontalScroll(rememberScrollState()).testTag("video-book-title"),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
        if (state.author.isNotEmpty()) {
            Text(
                text = state.author,
                modifier =
                    Modifier.fillMaxWidth().padding(start = 8.dp).testTag("video-book-author"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
