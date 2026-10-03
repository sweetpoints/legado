package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable
fun MangaEpaperScreen(
    state: MangaEpaperUiState,
    onThresholdChanged: (Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                stringResource(R.string.manga_epaper_stting),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${stringResource(R.string.manga_epaper_value)}: ${state.threshold}",
                modifier = Modifier.padding(top = 16.dp).testTag("manga-epaper-value"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onThresholdChanged(state.threshold - 1) },
                    enabled = state.threshold > 0,
                    modifier = Modifier.testTag("manga-epaper-minus"),
                ) {
                    Icon(painterResource(R.drawable.ic_reduce), stringResource(R.string.reduce))
                }
                Slider(
                    value = state.threshold.toFloat(),
                    onValueChange = { onThresholdChanged(it.roundToInt()) },
                    valueRange = 0f..255f,
                    steps = 254,
                    modifier = Modifier.weight(1f).testTag("manga-epaper-slider"),
                )
                IconButton(
                    onClick = { onThresholdChanged(state.threshold + 1) },
                    enabled = state.threshold < 255,
                    modifier = Modifier.testTag("manga-epaper-plus"),
                ) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.plus))
                }
            }
            if (state.isLoading) CircularProgressIndicator(Modifier.testTag("manga-epaper-loading"))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, modifier = Modifier.testTag("manga-epaper-retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
