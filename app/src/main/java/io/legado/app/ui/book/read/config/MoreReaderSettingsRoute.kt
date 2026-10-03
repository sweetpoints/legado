package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.preferences.MoreReaderSetting

@Composable
fun MoreReaderSettingsRoute(
    viewModel: MoreReaderSettingsViewModel,
    visibleSettings: List<MoreReaderSetting>,
    background: Color,
    slopSummary: String,
    bookmarkSummary: String,
    onToggle: (MoreReaderSetting.Toggle, Boolean) -> Unit,
    onChoice: (MoreReaderSetting.Choice, String) -> Unit,
    onSeekBar: (MoreReaderSetting.SeekBar, Int) -> Unit,
    onAction: (MoreReaderSetting.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MoreReaderSettingsScreen(
        state = state,
        visibleSettings = visibleSettings,
        background = background,
        slopSummary = slopSummary,
        bookmarkSummary = bookmarkSummary,
        onToggle = onToggle,
        onChoice = onChoice,
        onSeekBar = onSeekBar,
        onAction = onAction,
        onRetry = viewModel::refresh,
        modifier = modifier,
    )
}
