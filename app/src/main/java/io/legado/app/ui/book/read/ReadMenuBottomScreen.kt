package io.legado.app.ui.book.read

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import kotlin.math.roundToInt

internal data class ReadMenuBottomState(
    val progress: Int = 0,
    val maximum: Int = 0,
    val previousEnabled: Boolean = false,
    val nextEnabled: Boolean = false,
    val autoPage: Boolean = false,
    val nightTheme: Boolean = false,
    val showMemo: Boolean = false,
    val eInk: Boolean = false,
    val background: Color = Color.White,
    val foreground: Color = Color.Black,
    val pendingChapter: Int? = null,
)

internal enum class ReadMenuAction {
    Search,
    AutoPage,
    ReplaceRule,
    NightTheme,
    PreviousChapter,
    NextChapter,
    Catalog,
    ReadAloud,
    ReadAloudSettings,
    Style,
    Settings,
    Memo,
}

@Composable
internal fun ReadMenuBottomScreen(
    state: ReadMenuBottomState,
    onAction: (ReadMenuAction) -> Unit,
    onProgressDragging: (Boolean) -> Unit,
    onProgressCommit: (Int) -> Unit,
    onChapterConfirm: () -> Unit,
    onChapterCancel: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FloatingMenuAction(
                R.drawable.ic_search,
                R.string.search_content,
                "reader-search",
                state,
            ) {
                onAction(ReadMenuAction.Search)
            }
            FloatingMenuAction(
                if (state.autoPage) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page,
                if (state.autoPage) R.string.auto_next_page_stop else R.string.auto_next_page,
                "reader-auto-page",
                state,
            ) {
                onAction(ReadMenuAction.AutoPage)
            }
            FloatingMenuAction(
                R.drawable.ic_find_replace,
                R.string.replace_rule_title,
                "reader-replace",
                state,
            ) {
                onAction(ReadMenuAction.ReplaceRule)
            }
            FloatingMenuAction(
                if (state.nightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness,
                R.string.dark_theme,
                "reader-night",
                state,
            ) {
                onAction(ReadMenuAction.NightTheme)
            }
        }
        Surface(
            color = state.background,
            contentColor = state.foreground,
            border = if (state.eInk) BorderStroke(1.dp, state.foreground) else null,
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 7.dp)) {
                Row(
                    Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChapterButton(
                        R.string.previous_chapter,
                        "reader-previous",
                        state.previousEnabled,
                        state.foreground,
                    ) {
                        onAction(ReadMenuAction.PreviousChapter)
                    }
                    var sliderValue by
                        remember(state.progress, state.maximum, state.pendingChapter) {
                            mutableFloatStateOf(
                                state.progress.coerceIn(0, state.maximum.coerceAtLeast(0)).toFloat()
                            )
                        }
                    val interactions = remember { MutableInteractionSource() }
                    val draggingCallback by rememberUpdatedState(onProgressDragging)
                    LaunchedEffect(interactions) {
                        try {
                            interactions.interactions.collect { interaction ->
                                when (interaction) {
                                    is DragInteraction.Start -> draggingCallback(true)
                                    is DragInteraction.Stop,
                                    is DragInteraction.Cancel -> draggingCallback(false)
                                }
                            }
                        } finally {
                            // A cancelled gesture or disposed menu must restore outside-tap
                            // dismissal.
                            draggingCallback(false)
                        }
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            onProgressDragging(false)
                            onProgressCommit(sliderValue.roundToInt())
                        },
                        modifier = Modifier.weight(1f).testTag("reader-progress"),
                        enabled = state.maximum > 0,
                        valueRange = 0f..state.maximum.coerceAtLeast(1).toFloat(),
                        interactionSource = interactions,
                    )
                    ChapterButton(
                        R.string.next_chapter,
                        "reader-next",
                        state.nextEnabled,
                        state.foreground,
                    ) {
                        onAction(ReadMenuAction.NextChapter)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MenuAction(
                        R.drawable.ic_toc,
                        R.string.chapter_list,
                        "reader-catalog",
                        onClick = { onAction(ReadMenuAction.Catalog) },
                    )
                    MenuAction(
                        R.drawable.ic_read_aloud,
                        R.string.read_aloud,
                        "reader-read-aloud",
                        onClick = { onAction(ReadMenuAction.ReadAloud) },
                        onLongClick = { onAction(ReadMenuAction.ReadAloudSettings) },
                    )
                    MenuAction(
                        R.drawable.ic_interface_setting,
                        R.string.interface_setting,
                        "reader-style",
                        onClick = { onAction(ReadMenuAction.Style) },
                    )
                    MenuAction(
                        R.drawable.ic_settings,
                        R.string.setting,
                        "reader-settings",
                        onClick = { onAction(ReadMenuAction.Settings) },
                    )
                    if (state.showMemo)
                        MenuAction(
                            R.drawable.ic_edit,
                            R.string.book_memo,
                            "reader-memo",
                            onClick = { onAction(ReadMenuAction.Memo) },
                        )
                }
            }
        }
    }
    if (state.pendingChapter != null) {
        AlertDialog(
            onDismissRequest = onChapterCancel,
            title = { Text("章节跳转确认") },
            text = { Text("确定要跳转章节吗？") },
            confirmButton = {
                TextButton(onChapterConfirm, Modifier.testTag("reader-chapter-confirm")) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onChapterCancel) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ChapterButton(
    label: Int,
    tag: String,
    enabled: Boolean,
    foreground: Color,
    onClick: () -> Unit,
) {
    TextButton(onClick, Modifier.testTag(tag), enabled = enabled) {
        Text(
            stringResource(label),
            color = if (enabled) foreground else foreground.copy(alpha = .4f),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun FloatingMenuAction(
    icon: Int,
    label: Int,
    tag: String,
    state: ReadMenuBottomState,
    onClick: () -> Unit,
) {
    Surface(
        onClick,
        Modifier.size(48.dp).testTag(tag),
        shape = CircleShape,
        color = state.background,
        contentColor = state.foreground,
        shadowElevation = if (state.eInk) 0.dp else 2.dp,
    ) {
        Icon(painterResource(icon), stringResource(label), Modifier.padding(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuAction(
    icon: Int,
    label: Int,
    tag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.width(60.dp)
            .testTag(tag)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(22.dp))
        Text(
            stringResource(label),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
