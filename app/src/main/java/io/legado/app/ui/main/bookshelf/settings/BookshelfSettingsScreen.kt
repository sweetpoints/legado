package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.preferences.BookshelfSettingsDraft
import kotlin.math.roundToInt

@Composable
internal fun BookshelfSettingsScreen(
    state: BookshelfSettingsDraft,
    onEdit: (BookshelfSettingsDraft) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.bookshelf_layout),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                ShelfChoice(
                    stringResource(R.string.group_style),
                    stringArrayResource(R.array.group_style).toList(),
                    state.groupStyle,
                    "group",
                ) {
                    onEdit(state.copy(groupStyle = it))
                }
                ShelfChoice(
                    stringResource(R.string.read_progress),
                    stringArrayResource(R.array.bookshelf_read_progress).toList(),
                    state.progress,
                    "progress",
                ) {
                    onEdit(state.copy(progress = it))
                }
                ShelfSwitch(R.string.show_unread, "unread", state.unread) {
                    onEdit(state.copy(unread = it))
                }
                ShelfSwitch(R.string.show_last_update_time, "latest", state.latest) {
                    onEdit(state.copy(latest = it))
                }
                ShelfSwitch(R.string.show_wait_up_count, "wait", state.waitCount) {
                    onEdit(state.copy(waitCount = it))
                }
                ShelfSwitch(R.string.show_bookshelf_fast_scroller, "fast", state.fastScroll) {
                    onEdit(state.copy(fastScroll = it))
                }
                ShelfSwitch(R.string.recent_reading, "recent", state.recent) {
                    onEdit(state.copy(recent = it))
                }
                ShelfSwitch(R.string.bookshelf_statistics, "stats", state.stats) {
                    onEdit(state.copy(stats = it))
                }
                val layouts =
                    listOf(
                            R.string.layout_list,
                            R.string.layout_list_compact,
                            R.string.layout_grid2,
                            R.string.layout_grid3,
                            R.string.layout_grid4,
                            R.string.layout_grid5,
                            R.string.layout_grid6,
                        )
                        .map { stringResource(it) }
                val sorts =
                    listOf(
                            R.string.bookshelf_px_0,
                            R.string.bookshelf_px_1,
                            R.string.bookshelf_px_2,
                            R.string.bookshelf_px_3,
                            R.string.bookshelf_px_4,
                            R.string.bookshelf_px_5,
                        )
                        .map { stringResource(it) }
                ShelfChoice(stringResource(R.string.view), layouts, state.layout, "layout") {
                    onEdit(state.copy(layout = it))
                }
                ShelfChoice(stringResource(R.string.sort), sorts, state.sort, "sort") {
                    onEdit(state.copy(sort = it))
                }
                if (state.layout >= 2)
                    ShelfChoice(
                        stringResource(R.string.book_name),
                        listOf(
                            stringResource(R.string.show),
                            stringResource(R.string.hide),
                            stringResource(R.string.overlay),
                        ),
                        state.title,
                        "title",
                    ) {
                        onEdit(state.copy(title = it))
                    }
                val marginLabel = stringResource(R.string.margin)
                Text(
                    "$marginLabel: ${state.margin}",
                    Modifier.testTag("shelf-settings-margin-value"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onEdit(state.copy(margin = state.margin - 1)) },
                        enabled = state.margin > 0,
                        modifier =
                            Modifier.testTag("shelf-settings-margin-minus").semantics {
                                contentDescription = "$marginLabel −1"
                            },
                    ) {
                        Text("−")
                    }
                    Slider(
                        state.margin.toFloat(),
                        { onEdit(state.copy(margin = it.roundToInt())) },
                        valueRange = 0f..60f,
                        steps = 59,
                        modifier =
                            Modifier.weight(1f).testTag("shelf-settings-margin").semantics {
                                contentDescription = marginLabel
                            },
                    )
                    IconButton(
                        onClick = { onEdit(state.copy(margin = state.margin + 1)) },
                        enabled = state.margin < 60,
                        modifier =
                            Modifier.testTag("shelf-settings-margin-plus").semantics {
                                contentDescription = "$marginLabel +1"
                            },
                    ) {
                        Text("+")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("shelf-settings-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.testTag("shelf-settings-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}

@Composable
private fun ShelfSwitch(label: Int, tag: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("shelf-settings-$tag")
            .toggleable(checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun ShelfChoice(
    label: String,
    options: List<String>,
    selected: Int,
    tag: String,
    change: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Box {
            TextButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("shelf-settings-$tag"),
            ) {
                Text(options[selected])
            }
            DropdownMenu(expanded, { expanded = false }) {
                options.forEachIndexed { index, text ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            change(index)
                        },
                        modifier =
                            Modifier.testTag("shelf-settings-$tag-$index").semantics {
                                this.selected = selected == index
                            },
                    )
                }
            }
        }
    }
}
