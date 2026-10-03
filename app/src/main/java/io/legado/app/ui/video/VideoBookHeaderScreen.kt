package io.legado.app.ui.video

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

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
                color = colorResource(R.color.primaryText),
                fontSize = 20.sp,
                lineHeight = 24.sp,
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
                color = colorResource(R.color.tv_text_summary),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
