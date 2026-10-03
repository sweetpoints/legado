package io.legado.app.ui.book.read

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import kotlin.math.roundToInt

enum class ReaderPopup {
    Source,
    ChangeSource,
    Refresh,
    Overflow,
    More,
}

data class ReaderPopupEntry(
    val title: String,
    val value: String,
    val enabled: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean = false,
)

data class ReaderToolbarAction(
    val id: Int,
    val icon: Int,
    val title: String,
    val enabled: Boolean = true,
)

internal data class ReadMenuTopState(
    val title: String = "",
    val chapterName: String = "",
    val chapterUrl: String = "",
    val chapterNameOnly: Boolean = false,
    val showAddition: Boolean = false,
    val sourceName: String = "",
    val localBook: Boolean = false,
    val showCustomButton: Boolean = false,
    val actions: List<ReaderToolbarAction> = emptyList(),
    val showBrightness: Boolean = true,
    val brightnessAutomatic: Boolean = true,
    val brightness: Int = 0,
    val brightnessRight: Boolean = false,
    val background: Color = Color.White,
    val foreground: Color = Color.Black,
    val additionForeground: Color = Color.Black,
    val popup: ReaderPopup? = null,
    val popupEntries: List<ReaderPopupEntry> = emptyList(),
    val browserPrompt: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReadMenuScreen(
    visible: Boolean,
    animate: Boolean,
    dragging: Boolean,
    top: ReadMenuTopState,
    bottom: ReadMenuBottomState,
    dismiss: () -> Unit,
    back: () -> Unit,
    bookInfo: () -> Unit,
    chapterClick: () -> Unit,
    chapterLongClick: () -> Unit,
    customClick: (Boolean) -> Unit,
    toolbarAction: (Int, Boolean) -> Unit,
    openPopup: (ReaderPopup) -> Unit,
    dismissPopup: () -> Unit,
    popupAction: (String) -> Unit,
    toggleBrightness: () -> Unit,
    brightnessChange: (Int) -> Unit,
    brightnessCommit: (Int) -> Unit,
    swapBrightness: () -> Unit,
    bottomAction: (ReadMenuAction) -> Unit,
    progressDragging: (Boolean) -> Unit,
    progressCommit: (Int) -> Unit,
    chapterConfirm: () -> Unit,
    chapterCancel: () -> Unit,
) {
    val duration = if (animate) 150 else 0
    Box(Modifier.fillMaxSize()) {
        if (visible)
            Box(
                Modifier.fillMaxSize()
                    .testTag("reader-menu-background")
                    .combinedClickable(enabled = !dragging, onClick = dismiss)
            )
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(duration)) { -it },
            exit = slideOutVertically(tween(duration)) { -it },
        ) {
            Surface(
                color = top.background,
                contentColor = top.foreground,
                border =
                    if (bottom.eInk) androidx.compose.foundation.BorderStroke(1.dp, top.foreground)
                    else null,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(back) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                stringResource(R.string.back),
                            )
                        }
                        Text(
                            top.title,
                            Modifier.weight(1f).combinedClickable(onClick = bookInfo),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 18.sp,
                        )
                        top.actions.forEach { action ->
                            Box {
                                Icon(
                                    painterResource(action.icon),
                                    action.title,
                                    Modifier.size(48.dp)
                                        .testTag("reader-toolbar-${action.id}")
                                        .combinedClickable(
                                            enabled = action.enabled,
                                            onClick = { toolbarAction(action.id, false) },
                                            onLongClick = { toolbarAction(action.id, true) },
                                        )
                                        .padding(12.dp),
                                )
                            }
                        }
                        Box {
                            IconButton(
                                { openPopup(ReaderPopup.Overflow) },
                                Modifier.testTag("reader-overflow"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_more_vert),
                                    stringResource(R.string.more_menu),
                                )
                            }
                            ReaderDropdown(top, ReaderPopup.Overflow, dismissPopup, popupAction)
                            ReaderDropdown(top, ReaderPopup.More, dismissPopup, popupAction)
                            ReaderDropdown(top, ReaderPopup.ChangeSource, dismissPopup, popupAction)
                            ReaderDropdown(top, ReaderPopup.Refresh, dismissPopup, popupAction)
                        }
                    }
                    if (top.showAddition)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReaderChapterAddition(
                                top,
                                Modifier.weight(1f),
                                chapterClick,
                                chapterLongClick,
                            )
                            if (top.showCustomButton)
                                Icon(
                                    painterResource(R.drawable.ic_custom),
                                    stringResource(R.string.custom_button),
                                    Modifier.size(48.dp)
                                        .combinedClickable(
                                            onClick = { customClick(false) },
                                            onLongClick = { customClick(true) },
                                        )
                                        .padding(12.dp),
                                )
                            if (!top.localBook)
                                Box {
                                    Text(
                                        top.sourceName,
                                        Modifier.width(120.dp)
                                            .combinedClickable(
                                                onClick = { openPopup(ReaderPopup.Source) }
                                            )
                                            .padding(8.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    ReaderDropdown(
                                        top,
                                        ReaderPopup.Source,
                                        dismissPopup,
                                        popupAction,
                                    )
                                }
                        }
                }
            }
        }
        if (visible && top.showBrightness) {
            Surface(
                Modifier.align(
                        if (top.brightnessRight) Alignment.CenterEnd else Alignment.CenterStart
                    )
                    .padding(horizontal = 16.dp)
                    .width(48.dp)
                    .fillMaxHeight(.55f),
                color = bottom.background.copy(alpha = .5f),
                contentColor = bottom.foreground,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(toggleBrightness, Modifier.testTag("reader-brightness-auto")) {
                        Icon(
                            painterResource(R.drawable.ic_brightness_auto),
                            stringResource(R.string.brightness_auto),
                            tint =
                                if (top.brightnessAutomatic)
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary
                                else bottom.foreground.copy(alpha = .4f),
                        )
                    }
                    var value by
                        remember(top.brightness) { mutableFloatStateOf(top.brightness.toFloat()) }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Slider(
                            value,
                            {
                                value = it
                                brightnessChange(it.roundToInt())
                            },
                            modifier =
                                Modifier.weight(1f)
                                    .width(48.dp)
                                    .testTag("reader-brightness")
                                    .verticalSlider(),
                            enabled = !top.brightnessAutomatic,
                            valueRange = 0f..255f,
                            onValueChangeFinished = { brightnessCommit(value.roundToInt()) },
                        )
                    }

                    IconButton(swapBrightness) {
                        Icon(
                            painterResource(R.drawable.ic_swap_horiz),
                            stringResource(R.string.adjust_pos),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(duration)) { it },
            exit = slideOutVertically(tween(duration)) { it },
        ) {
            ReadMenuBottomScreen(
                bottom,
                bottomAction,
                progressDragging,
                progressCommit,
                chapterConfirm,
                chapterCancel,
            )
        }
    }
}

@Composable
private fun ReaderDropdown(
    state: ReadMenuTopState,
    popup: ReaderPopup,
    dismiss: () -> Unit,
    click: (String) -> Unit,
) {
    DropdownMenu(state.popup == popup, dismiss) {
        state.popupEntries.forEach { entry ->
            DropdownMenuItem(
                text = { Text(entry.title) },
                onClick = { click(entry.value) },
                enabled = entry.enabled,
                modifier =
                    Modifier.testTag("reader-menu-item-${entry.value}").semantics {
                        if (entry.checkable)
                            toggleableState =
                                if (entry.checked) ToggleableState.On else ToggleableState.Off
                    },
                trailingIcon =
                    if (entry.checkable) ({ Text(if (entry.checked) "✓" else "") }) else null,
            )
        }
    }
}

/**
 * Measure horizontally, then rotate within the measured vertical slot; RTL cannot invert
 * brightness.
 */
private fun Modifier.verticalSlider(): Modifier = layout { measurable, constraints ->
    val placeable =
        measurable.measure(
            constraints.copy(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
        )
    layout(placeable.height, placeable.width) {
        placeable.place(
            (placeable.height - placeable.width) / 2,
            (placeable.width - placeable.height) / 2,
        )
    }
}
    .graphicsLayer(rotationZ = -90f)

@Composable
internal fun ReadChapterBrowserPrompt(dismiss: () -> Unit, choose: (Boolean) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.open_fun)) },
        text = { Text(stringResource(R.string.use_browser_open)) },
        confirmButton = {
            androidx.compose.material3.TextButton({ choose(true) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton({ choose(false) }) {
                Text(stringResource(android.R.string.no))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderChapterAddition(
    top: ReadMenuTopState,
    modifier: Modifier,
    click: () -> Unit,
    longClick: () -> Unit,
) {
    val nameModifier =
        Modifier.testTag("reader-chapter-name")
            .combinedClickable(onClick = click, onLongClick = longClick)
    val urlModifier =
        Modifier.testTag("reader-chapter-url")
            .graphicsLayer { alpha = if (top.chapterNameOnly) 0f else 1f }
            .combinedClickable(onClick = click, onLongClick = longClick)
    if (top.chapterNameOnly && top.chapterUrl.isNotEmpty()) {
        Box(modifier.height(40.dp).padding(horizontal = 10.dp)) {
            Text(
                top.chapterName,
                nameModifier.align(Alignment.CenterStart),
                color = top.additionForeground,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                top.chapterUrl,
                urlModifier.align(Alignment.BottomStart),
                color = top.additionForeground,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else
        Column(modifier.padding(horizontal = 10.dp)) {
            if (top.chapterName.isNotEmpty())
                Text(
                    top.chapterName,
                    nameModifier,
                    color = top.additionForeground,
                    fontSize = if (top.chapterNameOnly) 16.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            if (top.chapterUrl.isNotEmpty())
                Text(
                    top.chapterUrl,
                    urlModifier,
                    color = top.additionForeground,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
}
