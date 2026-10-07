package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

class BottomBarAssignmentActions(
    val name: (String, Int, Int) -> Unit = { _, _, _ -> },
    val palette: (String, Boolean) -> Unit = { _, _ -> },
    val choose: (String?) -> Unit = {},
    val cancelPalette: () -> Unit = {},
    val save: () -> Unit = {},
    val close: () -> Unit = {},
    val retry: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomBarAssignmentScreen(
    state: BottomBarAssignmentState,
    editing: Boolean,
    actions: BottomBarAssignmentActions,
    image: @Composable (String, Modifier) -> Unit =
        { _, _ ->
        },
) {
    var name by remember {
        mutableStateOf(TextFieldValue(state.name, TextRange(state.start, state.end)))
    }
    SideEffect {
        if (name.text != state.name || name.selection != TextRange(state.start, state.end))
            name = TextFieldValue(state.name, TextRange(state.start, state.end))
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (editing) R.string.edit else R.string.bottom_bar_skin_assign
                        ),
                        Modifier,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        actions.close,
                        Modifier.testTag("bar-assignment-back"),
                        enabled = !state.busy && !state.finished,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        actions.save,
                        Modifier.testTag("bar-assignment-save"),
                        enabled = state.loaded && !state.busy && !state.finished,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                    ) {
                        Text(
                            stringResource(R.string.bottom_bar_skin_save),
                            color = LocalContentColor.current,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            if (!state.loaded || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(
                name,
                {
                    name = it
                    actions.name(it.text, it.selection.start, it.selection.end)
                },
                Modifier.fillMaxWidth().padding(12.dp).testTag("bar-assignment-name"),
                singleLine = true,
                label = { Text(stringResource(R.string.bottom_bar_skin_name)) },
                enabled = !state.busy && !state.finished,
            )
            state.issue?.let { issue ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (issue == BottomBarAssignmentIssue.NeedSelected)
                                R.string.bottom_bar_skin_need_selected
                            else R.string.bottom_bar_skin_invalid
                        ),
                        Modifier.weight(1f).padding(12.dp).testTag("bar-assignment-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!state.loaded)
                        TextButton(actions.retry, enabled = !state.busy) {
                            Text(stringResource(R.string.retry))
                        }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp)) {
                items(state.slots, key = { it.slot }) { row ->
                    val title =
                        stringResource(
                            when (row.slot) {
                                "bookshelf" -> R.string.bookshelf
                                "home" -> R.string.discovery
                                "notes" -> R.string.rss
                                else -> R.string.my
                            }
                        )
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(8.dp)
                            .testTag("bar-assignment-slot-" + row.slot),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, Modifier.weight(1f))
                        AssignThumbnail(
                            row.slot,
                            false,
                            row.selected,
                            !state.busy && !state.finished,
                            title,
                            actions,
                            image,
                        )
                        Spacer(Modifier.width(16.dp))
                        AssignThumbnail(
                            row.slot,
                            true,
                            row.normal,
                            row.selected != null && !state.busy && !state.finished,
                            title,
                            actions,
                            image,
                        )
                    }
                }
            }
        }
    }
    state.palette?.let { slot ->
        val current = state.slots.find { it.slot == slot }
        val allowClear = (if (state.normal) current?.normal else current?.selected) != null
        val columns = (LocalConfiguration.current.screenWidthDp / 72).coerceIn(2, 4)
        ModalBottomSheet(
            onDismissRequest = actions.cancelPalette,
            modifier = Modifier.testTag("bar-assignment-palette"),
        ) {
            if (allowClear)
                TextButton(
                    { actions.choose(null) },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("bar-assignment-clear"),
                ) {
                    Text(stringResource(R.string.clear))
                }
            LazyVerticalGrid(
                GridCells.Fixed(columns),
                Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(state.images, key = { it }) { filename ->
                    val description = stringResource(R.string.bottom_bar_skin_pick_image_desc)
                    Box(
                        Modifier.height(68.dp)
                            .padding(6.dp)
                            .clickable { actions.choose(filename) }
                            .semantics { contentDescription = description }
                            .testTag("bar-assignment-image-$filename"),
                        contentAlignment = Alignment.Center,
                    ) {
                        image(filename, Modifier.size(56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssignThumbnail(
    slot: String,
    normal: Boolean,
    filename: String?,
    enabled: Boolean,
    title: String,
    actions: BottomBarAssignmentActions,
    image: @Composable (String, Modifier) -> Unit,
) {
    val label =
        stringResource(
            if (normal) R.string.bottom_bar_skin_slot_normal
            else R.string.bottom_bar_skin_slot_selected
        )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (normal && !enabled) .4f else 1f),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box(
            Modifier.size(48.dp)
                .clickable(enabled = enabled) { actions.palette(slot, normal) }
                .semantics { contentDescription = "$title $label" }
                .testTag("bar-assignment-$slot-${if (normal) "normal" else "selected"}"),
            contentAlignment = Alignment.Center,
        ) {
            if (filename == null)
                Icon(
                    painterResource(R.drawable.ic_add),
                    null,
                    tint = androidx.compose.ui.graphics.Color(0xff999999),
                )
            else image(filename, Modifier.size(44.dp))
        }
    }
}
