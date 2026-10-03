package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.ReplaceRuleImportItem
import io.legado.app.data.repository.ReplaceRuleImportStatus

internal fun replaceRuleImportStatus(item: ReplaceRuleImportItem): Int =
    when (item.status) {
        ReplaceRuleImportStatus.New -> R.string.import_status_new
        ReplaceRuleImportStatus.Update -> R.string.import_status_update
        ReplaceRuleImportStatus.Existing -> R.string.import_status_exist
    }

@Composable
internal fun ImportReplaceRuleScreen(
    state: ImportReplaceRuleState,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onCode: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onGroup: () -> Unit = {},
    onGroupDraft: (String) -> Unit = {},
    onAddGroup: (Boolean) -> Unit = {},
    onAcceptGroup: () -> Unit = {},
    onCloseGroup: () -> Unit = {},
) {
    val enabled = !state.loading && !state.busy && !state.finished
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
                .imePadding()
        ) {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Text(
                    stringResource(R.string.import_replace_rule),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            TextButton(
                onGroup,
                enabled = !state.busy && !state.finished,
                modifier = Modifier.fillMaxWidth().testTag("replace-import-group"),
            ) {
                Text(
                    if (state.group.isBlank()) stringResource(R.string.diy_source_group)
                    else
                        (if (state.addGroup) "+" else "") +
                            stringResource(R.string.diy_edit_source_group_title, state.group)
                )
            }
            if (state.loading || state.busy)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("replace-import-progress"))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).testTag("replace-import-list")
            ) {
                state.error?.let { error ->
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            if (state.items.isEmpty())
                                TextButton(onRetry, enabled = !state.loading && !state.busy) {
                                    Text(stringResource(R.string.retry))
                                }
                        }
                    }
                }
                if (!state.loading && state.items.isEmpty() && state.error == null)
                    item {
                        Text(stringResource(R.string.wrong_format), Modifier.padding(16.dp))
                    }
                items(state.items, key = { it.key }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("replace-import-row-${item.key}")
                            .clickable(
                                enabled = enabled,
                                role = Role.Checkbox,
                                onClick = { onToggle(item.key) },
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.key in state.selected,
                            onCheckedChange = { onToggle(item.key) },
                            enabled = enabled,
                            modifier = Modifier.testTag("replace-import-check-${item.key}"),
                        )
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(replaceRuleImportStatus(item)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            { onCode(item.key) },
                            enabled = enabled,
                            modifier = Modifier.testTag("replace-import-code-${item.key}"),
                        ) {
                            Text(stringResource(R.string.open))
                        }
                    }
                }
            }
            // Separate rows keep the selected count and 48dp actions reachable on narrow windows.
            TextButton(
                onToggleAll,
                enabled = enabled && state.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("replace-import-select-all"),
            ) {
                Text(
                    stringResource(
                        if (state.isSelectAll) R.string.select_cancel_count
                        else R.string.select_all_count,
                        state.selectCount,
                        state.items.size,
                    )
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onCancel,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("replace-import-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onConfirm,
                    enabled = enabled && (state.error == null || state.items.isNotEmpty()),
                    modifier = Modifier.testTag("replace-import-confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }

    if (state.groupOpen && !state.finished)
        AlertDialog(
            onDismissRequest = onCloseGroup,
            title = { Text(stringResource(R.string.diy_edit_source_group)) },
            text = {
                Column(
                    Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.add_group))
                            Text(
                                stringResource(R.string.custom_group_summary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            state.addGroupDraft,
                            onAddGroup,
                            Modifier.testTag("replace-import-add-group"),
                        )
                    }
                    OutlinedTextField(
                        state.groupDraft,
                        onGroupDraft,
                        label = { Text(stringResource(R.string.group_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("replace-import-group-name"),
                        singleLine = true,
                    )
                    LazyColumn(
                        Modifier.heightIn(max = 180.dp).testTag("replace-import-group-options")
                    ) {
                        items(
                            state.groups.filter { it.contains(state.groupDraft, ignoreCase = true) }
                        ) { group ->
                            TextButton({ onGroupDraft(group) }, Modifier.fillMaxWidth()) {
                                Text(group)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onAcceptGroup, Modifier.testTag("replace-import-group-ok")) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onCloseGroup, Modifier.testTag("replace-import-group-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}
