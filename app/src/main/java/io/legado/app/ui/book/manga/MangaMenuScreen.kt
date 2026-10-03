package io.legado.app.ui.book.manga

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.MangaReaderSetting

internal enum class MangaMenuAction {
    BookInfo,
    Catalog,
    ChangeSource,
    Refresh,
    Download,
    Preload,
    ColorFilter,
    Footer,
    EpaperSettings,
    Browser,
    AutoPage,
    AutoScroll,
    AutoSpeed,
}

private data class MangaMenuEntry(
    val label: Int,
    val checked: Boolean? = null,
    val action: MangaMenuAction? = null,
    val setting: MangaReaderSetting? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MangaMenuScreen(
    state: MangaReaderUiState,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onAction: (MangaMenuAction) -> Unit,
    onSetting: (MangaReaderSetting, Boolean) -> Unit,
    onChapter: (Int) -> Unit,
    onPage: (Int) -> Unit,
    onPreload: (Int) -> Unit,
    onAutoSpeed: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.menuOverflowRevision) {
        if (state.menuOverflowRevision > 0) expanded = true
    }
    var numericAction by remember { mutableStateOf<MangaMenuAction?>(null) }
    var browserChoice by remember { mutableStateOf(false) }
    val settings = state.settings
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = .35f))
                .clickable(onClick = onClose)
        )
        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            Column(Modifier.statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onExit) { Text(stringResource(R.string.back)) }
                    Text(
                        state.book?.name.orEmpty(),
                        modifier =
                            Modifier.weight(1f).clickable { onAction(MangaMenuAction.BookInfo) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box {
                        TextButton(onClick = { expanded = true }) {
                            Text(stringResource(R.string.menu))
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.heightIn(max = 500.dp),
                        ) {
                            mangaMenuEntries(state).forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(entry.label)) },
                                    trailingIcon = {
                                        entry.checked?.let {
                                            Checkbox(checked = it, onCheckedChange = null)
                                        }
                                    },
                                    onClick = {
                                        expanded = false
                                        if (entry.setting != null) {
                                            onSetting(entry.setting, entry.checked != true)
                                        } else {
                                            val action = checkNotNull(entry.action)
                                            if (
                                                action == MangaMenuAction.Preload ||
                                                    action == MangaMenuAction.AutoSpeed
                                            ) {
                                                numericAction = action
                                            } else onAction(action)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                if (settings.showTitleAddition) {
                    Text(
                        state.chapterName,
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { onAction(MangaMenuAction.Browser) },
                                onLongClick = { browserChoice = true },
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.chapterUrl.orEmpty(),
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { onAction(MangaMenuAction.Browser) },
                                onLongClick = { browserChoice = true },
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp)) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (settings.horizontal && settings.rightToLeft) LayoutDirection.Rtl
                        else LayoutDirection.Ltr
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onChapter(-1) }, enabled = state.chapterIndex > 0) {
                            Text(stringResource(R.string.previous_chapter))
                        }
                        Slider(
                            value =
                                state.pageIndex
                                    .coerceIn(0, (state.imageCount - 1).coerceAtLeast(0))
                                    .toFloat(),
                            onValueChange = { onPage(it.toInt()) },
                            modifier = Modifier.weight(1f),
                            enabled = state.imageCount > 1,
                            valueRange = 0f..(state.imageCount - 1).coerceAtLeast(1).toFloat(),
                            steps = (state.imageCount - 2).coerceAtLeast(0),
                        )
                        TextButton(
                            onClick = { onChapter(1) },
                            enabled = state.chapterIndex + 1 < state.chapterCount,
                        ) {
                            Text(stringResource(R.string.next_chapter))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onAction(MangaMenuAction.ChangeSource) }) {
                        Text(
                            state.book?.sourceName ?: stringResource(R.string.book_source),
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { onAction(MangaMenuAction.Refresh) }) {
                        Text(stringResource(R.string.refresh))
                    }
                    TextButton(onClick = { onAction(MangaMenuAction.Catalog) }) {
                        Text(stringResource(R.string.chapter_list))
                    }
                }
            }
        }
    }
    numericAction?.let { action ->
        MangaReaderNumberDialog(
            title =
                if (action == MangaMenuAction.Preload) stringResource(R.string.pre_download)
                else stringResource(R.string.setting_manga_auto_page_speed),
            initialValue =
                if (action == MangaMenuAction.Preload) settings.preloadImages
                else settings.autoSpeed,
            minimum = if (action == MangaMenuAction.Preload) 0 else 1,
            onDismiss = { numericAction = null },
            onConfirm = {
                numericAction = null
                if (action == MangaMenuAction.Preload) onPreload(it) else onAutoSpeed(it)
            },
        )
    }
    if (browserChoice) {
        AlertDialog(
            onDismissRequest = { browserChoice = false },
            title = { Text(stringResource(R.string.open_fun)) },
            text = { Text(stringResource(R.string.use_browser_open)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        browserChoice = false
                        onSetting(MangaReaderSetting.ExternalBrowser, true)
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        browserChoice = false
                        onSetting(MangaReaderSetting.ExternalBrowser, false)
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

private fun mangaMenuEntries(state: MangaReaderUiState): List<MangaMenuEntry> {
    val settings = state.settings
    return buildList {
        add(MangaMenuEntry(R.string.offline_cache, action = MangaMenuAction.Download))
        add(MangaMenuEntry(R.string.pre_download, action = MangaMenuAction.Preload))
        add(
            MangaMenuEntry(
                R.string.disable_manga_scale,
                settings.disableZoom,
                setting = MangaReaderSetting.DisableZoom,
            )
        )
        add(
            MangaMenuEntry(
                R.string.manga_long_click_save_image,
                settings.longPressSave,
                setting = MangaReaderSetting.LongPressSave,
            )
        )
        add(
            MangaMenuEntry(
                R.string.disable_manga_click_scroll,
                settings.disableClickScroll,
                setting = MangaReaderSetting.DisableClickScroll,
            )
        )
        add(
            MangaMenuEntry(
                R.string.enable_auto_page_scroll,
                state.autoPage,
                action = MangaMenuAction.AutoPage,
            )
        )
        add(
            MangaMenuEntry(
                R.string.enable_auto_scroll,
                state.autoScroll,
                action = MangaMenuAction.AutoScroll,
            )
        )
        if (state.autoPage || state.autoScroll)
            add(
                MangaMenuEntry(
                    R.string.setting_manga_auto_page_speed,
                    action = MangaMenuAction.AutoSpeed,
                )
            )
        add(
            MangaMenuEntry(
                R.string.enable_manga_horizontal_scroll,
                settings.horizontal,
                setting = MangaReaderSetting.Horizontal,
            )
        )
        if (settings.horizontal) {
            add(
                MangaMenuEntry(
                    R.string.manga_right_to_left,
                    settings.rightToLeft,
                    setting = MangaReaderSetting.RightToLeft,
                )
            )
            if (!settings.disablePageAnimation)
                add(
                    MangaMenuEntry(
                        R.string.disable_horizontal_page_snap,
                        settings.disableSnap,
                        setting = MangaReaderSetting.DisableSnap,
                    )
                )
        }
        add(
            MangaMenuEntry(
                R.string.disable_manga_page_anim,
                settings.disablePageAnimation,
                setting = MangaReaderSetting.DisablePageAnimation,
            )
        )
        add(MangaMenuEntry(R.string.manga_footer_config, action = MangaMenuAction.Footer))
        add(MangaMenuEntry(R.string.manga_color_filter, action = MangaMenuAction.ColorFilter))
        add(
            MangaMenuEntry(
                R.string.hide_manga_title,
                settings.hideChapterTitle,
                setting = MangaReaderSetting.HideChapterTitle,
            )
        )
        add(
            MangaMenuEntry(
                R.string.manga_epaper,
                settings.epaper,
                setting = MangaReaderSetting.Epaper,
            )
        )
        if (settings.epaper)
            add(
                MangaMenuEntry(
                    R.string.manga_epaper_stting,
                    action = MangaMenuAction.EpaperSettings,
                )
            )
        add(
            MangaMenuEntry(
                R.string.enable_manga_gray,
                settings.grayscale,
                setting = MangaReaderSetting.Grayscale,
            )
        )
    }
}

@Composable
private fun MangaReaderNumberDialog(
    title: String,
    initialValue: Int,
    minimum: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    val value = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.all(Char::isDigit)) text = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onConfirm) },
                enabled = value != null && value in minimum..9999,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
