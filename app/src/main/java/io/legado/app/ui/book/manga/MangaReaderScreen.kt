package io.legado.app.ui.book.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.image.MangaImageRepository
import io.legado.app.data.preferences.MangaReaderSetting
import java.text.DecimalFormat

internal fun mangaProgressPercent(
    chapterIndex: Int,
    chapterCount: Int,
    pageIndex: Int,
    imageCount: Int,
): String {
    val format = DecimalFormat("0.0%")
    if (chapterCount == 0 || (imageCount == 0 && chapterIndex == 0)) return "0.0%"
    if (imageCount == 0) return format.format((chapterIndex + 1.0f) / chapterCount.toDouble())
    val percent =
        format.format(
            chapterIndex * 1.0f / chapterCount +
                1.0f / chapterCount * (pageIndex + 1) / imageCount.toDouble()
        )
    return if (
        percent == "100.0%" && (chapterIndex + 1 != chapterCount || pageIndex + 1 != imageCount)
    )
        "99.9%"
    else percent
}

@Composable
internal fun MangaReaderScreen(
    state: MangaReaderUiState,
    imageRepository: MangaImageRepository,
    readerActive: Boolean,
    timeText: String,
    onCurrentItem: (MangaReaderItem) -> Unit,
    onCommandHandled: (Long) -> Unit,
    onMenu: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
    onLongPress: (MangaReaderItem.Page) -> Unit,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    onMenuAction: (MangaMenuAction) -> Unit,
    onSetting: (MangaReaderSetting, Boolean) -> Unit,
    onChapter: (Int) -> Unit,
    onSkipPage: (Int) -> Unit,
    onPreload: (Int) -> Unit,
    onAutoSpeed: (Int) -> Unit,
    onCloudProgress: (Boolean) -> Unit,
    onResolveExit: (Boolean) -> Unit,
    onDismissExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        MangaViewportScreen(
            sessionKey = state.sessionId,
            items = state.items,
            bookUrl = state.book?.bookUrl.orEmpty(),
            sourceOrigin = state.book?.sourceOrigin,
            repository = imageRepository,
            options =
                MangaViewportOptions(
                    horizontal = settings.horizontal,
                    rightToLeft = settings.horizontal && settings.rightToLeft,
                    disableZoom = settings.disableZoom,
                    disableClickScroll = settings.disableClickScroll,
                    longPressSaveEnabled = settings.longPressSave,
                    disablePageAnimation = settings.disablePageAnimation,
                    snapPages = !settings.disableSnap,
                    isEInk = settings.isEInk,
                    epaperThreshold = if (settings.epaper) settings.threshold else null,
                    grayscale = settings.grayscale,
                    autoPageSeconds = if (state.autoPage) settings.autoSpeed else null,
                    autoScrollDistance = if (state.autoScroll) settings.autoSpeed else null,
                ),
            colorFilter = state.colorFilter,
            anchorIndex = state.anchorIndex,
            command = state.scrollCommand,
            readerActive = readerActive && !state.menuVisible && !state.loading,
            onCurrentItem = onCurrentItem,
            onCommandHandled = onCommandHandled,
            onMenu = { onMenu(true) },
            onPageTap = onPage,
            onLongPress = onLongPress,
            footer = {
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.error != null && !state.loading ->
                            TextButton(onClick = onRetry) {
                                Text("加载失败，点击重试")
                            }
                        state.nextLoading ->
                            if (settings.isEInk) Text(stringResource(R.string.loading))
                            else CircularProgressIndicator()
                        state.chapterIndex + 1 >= state.chapterCount -> Text("暂无章节了！")
                    }
                }
            },
        )
        if (!state.loading && !state.footer.hideFooter) {
            MangaReaderFooterScreen(state, timeText, Modifier.align(Alignment.BottomCenter))
        }
        if (state.loading) {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.error != null) {
                        Text(state.error, Modifier.padding(16.dp))
                        if (state.retryAllowed) TextButton(onClick = onRetry) { Text("重新加载") }
                    } else {
                        if (!settings.isEInk) CircularProgressIndicator()
                        Text(stringResource(R.string.loading), Modifier.padding(12.dp))
                    }
                }
            }
        }
        if (state.menuVisible) {
            MangaMenuScreen(
                state = state,
                onClose = { onMenu(false) },
                onExit = onExit,
                onAction = onMenuAction,
                onSetting = onSetting,
                onChapter = onChapter,
                onPage = onSkipPage,
                onPreload = onPreload,
                onAutoSpeed = onAutoSpeed,
            )
        }
    }
    if (state.exitPrompt) {
        AlertDialog(
            onDismissRequest = onDismissExit,
            title = { Text(stringResource(R.string.add_to_bookshelf)) },
            text = {
                Text(stringResource(R.string.check_add_bookshelf, state.book?.name.orEmpty()))
            },
            confirmButton = {
                TextButton(onClick = { onResolveExit(true) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onResolveExit(false) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    if (state.pendingCloudProgress != null) {
        AlertDialog(
            onDismissRequest = { onCloudProgress(false) },
            title = { Text(stringResource(R.string.get_book_progress)) },
            text = { Text(stringResource(R.string.cloud_progress_exceeds_current)) },
            confirmButton = {
                TextButton(onClick = { onCloudProgress(true) }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { onCloudProgress(false) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MangaReaderFooterScreen(
    state: MangaReaderUiState,
    timeText: String,
    modifier: Modifier,
) {
    val footer = state.footer
    val pageLabel = stringResource(R.string.manga_check_page_number)
    val chapterLabel = stringResource(R.string.manga_check_chapter)
    val progressLabel = stringResource(R.string.manga_check_progress)
    val page = state.footerPage
    val text =
        if (page == null) ""
        else
            buildString {
                if (!footer.hideChapterName) append(page.chapterName).append(' ')
                if (!footer.hidePageNumber) {
                    if (!footer.hidePageNumberLabel) append(pageLabel)
                    append("${page.pageIndex + 1}/${page.imageCount} ")
                }
                if (!footer.hideChapter) {
                    if (!footer.hideChapterLabel) append(chapterLabel)
                    append("${page.chapterIndex + 1}/${page.chapterCount} ")
                }
                if (!footer.hideProgressRatio) {
                    if (!footer.hideProgressRatioLabel) append(progressLabel)
                    append(
                        mangaProgressPercent(
                            page.chapterIndex,
                            page.chapterCount,
                            page.pageIndex,
                            page.imageCount,
                        )
                    )
                }
            }
    Box(
        modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        MangaOutlinedFooterText(
            text,
            modifier =
                Modifier.align(
                        if (footer.footerOrientation == 1) Alignment.Center
                        else Alignment.CenterStart
                    )
                    .padding(end = 46.dp),
            maxLines = 1,
        )
        MangaOutlinedFooterText(timeText, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun MangaOutlinedFooterText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val density = LocalDensity.current
    val outline = MaterialTheme.colorScheme.surface.copy(alpha = 200f / 255f)
    val foreground = MaterialTheme.colorScheme.onSurface.copy(alpha = 200f / 255f)
    val style =
        TextStyle(
            fontSize = 12.sp,
            shadow =
                Shadow(
                    color = MaterialTheme.colorScheme.outline,
                    offset = Offset(1f, 1f),
                    blurRadius = 2f,
                ),
        )
    Box(modifier) {
        Text(
            text,
            Modifier.clearAndSetSemantics {},
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = style.copy(color = outline, drawStyle = Stroke(with(density) { 2.dp.toPx() })),
        )
        Text(
            text,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = style.copy(color = foreground),
        )
    }
}
