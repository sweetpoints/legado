package io.legado.app.ui.highlight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.legado.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightGroupScreen(
    state: HighlightGroupState,
    rename: (String) -> Unit,
    delete: (String) -> Unit,
    name: (String) -> Unit,
    confirmRename: () -> Unit,
    cancel: () -> Unit,
    confirmDelete: () -> Unit,
    chooseMove: () -> Unit,
    move: (String?) -> Unit,
    retry: () -> Unit,
    close: () -> Unit,
) {
    BackHandler {
        if (!state.busy) {
            if (state.stage != HighlightGroupStage.None) cancel() else close()
        }
    }
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.highlight_rule_group_manage),
                        Modifier,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(
                        close,
                        Modifier.testTag("highlight-group-close"),
                        enabled = !state.busy,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_close),
                            stringResource(R.string.close),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("highlight-group-progress"))
            state.error?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    if (state.stage == HighlightGroupStage.None)
                        TextButton(
                            retry,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("highlight-group-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("highlight-group-list")) {
                items(state.groups, key = { it }) { group ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(group, Modifier.weight(1f).testTag("highlight-group-label-$group"))
                        TextButton(
                            { rename(group) },
                            enabled = !state.loading && !state.busy,
                            modifier = Modifier.testTag("highlight-group-edit-$group"),
                        ) {
                            Text(stringResource(R.string.edit))
                        }
                        TextButton(
                            { delete(group) },
                            enabled = !state.loading && !state.busy,
                            modifier = Modifier.testTag("highlight-group-delete-$group"),
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    when (state.stage) {
        HighlightGroupStage.None -> Unit
        HighlightGroupStage.Rename -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(state.source) { focus.requestFocus() }
            AlertDialog(
                onDismissRequest = { if (!state.busy) cancel() },
                title = { Text(stringResource(R.string.group_edit)) },
                text = {
                    Column {
                        OutlinedTextField(
                            state.name,
                            name,
                            Modifier.fillMaxWidth()
                                .focusRequester(focus)
                                .testTag("highlight-group-name"),
                            enabled = !state.busy,
                            singleLine = true,
                            label = { Text(stringResource(R.string.group_name)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { confirmRename() }),
                        )
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(
                        confirmRename,
                        enabled = !state.loading && !state.busy,
                        modifier = Modifier.testTag("highlight-group-rename-confirm"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        cancel,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("highlight-group-cancel"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        HighlightGroupStage.Delete ->
            AlertDialog(
                onDismissRequest = { if (!state.busy) cancel() },
                title = { Text(stringResource(R.string.highlight_rule_group_delete)) },
                text = {
                    Column {
                        Text(stringResource(R.string.highlight_rule_group_delete_message))
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(
                        confirmDelete,
                        enabled = !state.loading && !state.busy,
                        modifier = Modifier.testTag("highlight-group-delete-confirm"),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            cancel,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("highlight-group-cancel"),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            chooseMove,
                            enabled = !state.loading && !state.busy,
                            modifier = Modifier.testTag("highlight-group-choose-move"),
                        ) {
                            Text(stringResource(R.string.move_to_group))
                        }
                    }
                },
            )
        HighlightGroupStage.Move ->
            AlertDialog(
                onDismissRequest = { if (!state.busy) cancel() },
                title = { Text(stringResource(R.string.move_to_group)) },
                text = {
                    Column {
                        LazyColumn(
                            Modifier.heightIn(max = 320.dp)
                                .fillMaxWidth()
                                .testTag("highlight-group-targets")
                        ) {
                            items(
                                state.groups.filterNot { it == state.source },
                                key = { "group:$it" },
                            ) { target ->
                                TextButton(
                                    { move(target) },
                                    enabled = !state.loading && !state.busy,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("highlight-group-move-$target"),
                                ) {
                                    Text("[$target]")
                                }
                            }
                            item(key = "ungrouped") {
                                TextButton(
                                    { move(null) },
                                    enabled = !state.loading && !state.busy,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("highlight-group-move-none"),
                                ) {
                                    Text(stringResource(R.string.no_group))
                                }
                            }
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(
                        cancel,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("highlight-group-cancel"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
    }
}
