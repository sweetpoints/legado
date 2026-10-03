package io.legado.app.ui.book.manga.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlin.math.roundToInt

@Composable
internal fun MangaColorFilterScreen(
    state: MangaColorFilterUiState,
    onChange: (MangaColorChannel, Int) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.imePadding(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.manga_color_filter),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.loading) CircularProgressIndicator(Modifier.testTag("manga-filter-loading"))
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onRetry, Modifier.testTag("manga-filter-retry")) {
                    Text(stringResource(R.string.retry))
                }
            }
            ChannelControl(
                MangaColorChannel.BRIGHTNESS,
                stringResource(R.string.brightness),
                state.values.brightness,
                !state.finished && !state.loading,
                onChange,
            )
            ChannelControl(
                MangaColorChannel.RED,
                "R",
                state.values.red,
                !state.finished && !state.loading,
                onChange,
            )
            ChannelControl(
                MangaColorChannel.GREEN,
                "G",
                state.values.green,
                !state.finished && !state.loading,
                onChange,
            )
            ChannelControl(
                MangaColorChannel.BLUE,
                "B",
                state.values.blue,
                !state.finished && !state.loading,
                onChange,
            )
            ChannelControl(
                MangaColorChannel.ALPHA,
                "A",
                state.values.alpha,
                !state.finished && !state.loading,
                onChange,
            )
        }
    }
}

@Composable
private fun ChannelControl(
    channel: MangaColorChannel,
    title: String,
    value: Int,
    enabled: Boolean,
    onChange: (MangaColorChannel, Int) -> Unit,
) {
    val tag = "manga-filter-${channel.name.lowercase()}"
    val decreaseDescription = "$title ${stringResource(R.string.reduce)}"
    val increaseDescription = "$title ${stringResource(R.string.plus)}"
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f).padding(top = 16.dp))
            TextButton(
                { onChange(channel, value - 1) },
                Modifier.testTag("$tag-minus").semantics {
                    contentDescription = decreaseDescription
                },
                enabled = enabled && value > 0,
            ) {
                Text("−")
            }
            OutlinedTextField(
                text,
                {
                    if (it.isEmpty() || it.all(Char::isDigit)) {
                        if (it.isEmpty()) text = ""
                        else
                            it.toLongOrNull()?.let { number ->
                                val bounded = number.coerceIn(0, 255).toInt()
                                text = bounded.toString()
                                onChange(channel, bounded)
                            }
                    }
                },
                Modifier.width(88.dp).testTag("$tag-value"),
                enabled = enabled,
                singleLine = true,
                label = { Text(title) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(
                { onChange(channel, value + 1) },
                Modifier.testTag("$tag-plus").semantics {
                    contentDescription = increaseDescription
                },
                enabled = enabled && value < 255,
            ) {
                Text("+")
            }
        }
        Slider(
            value.toFloat(),
            { onChange(channel, it.roundToInt()) },
            Modifier.fillMaxWidth().testTag("$tag-slider").semantics { contentDescription = title },
            enabled = enabled,
            valueRange = 0f..255f,
            steps = 254,
        )
    }
}
