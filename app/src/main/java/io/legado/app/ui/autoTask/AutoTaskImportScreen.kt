package io.legado.app.ui.autoTask

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.AutoTaskImportStatus

@Composable internal fun AutoTaskImportScreen(state: AutoTaskImportState, toggle: (String) -> Unit,
    edit: (String) -> Unit, all: () -> Unit, clear: () -> Unit,
    confirm: () -> Unit, cancel: () -> Unit, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
            .testTag("auto-task-import")) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(stringResource(R.string.import_auto_task), Modifier.fillMaxWidth().padding(16.dp)
                    .testTag("auto-task-import-title"), style = MaterialTheme.typography.titleLarge)
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("auto-task-import-working"))
            state.error?.let { message ->
                Text("ImportError:$message", Modifier.padding(16.dp).testTag("auto-task-import-error"), color = MaterialTheme.colorScheme.error)
                if (state.items.isEmpty() && !state.loading) TextButton(onClick = retry, modifier = Modifier.testTag("auto-task-import-retry")) { Text(stringResource(R.string.retry)) }
            }
            LazyColumn(Modifier.weight(1f, fill = false).testTag("auto-task-import-list")) {
                items(state.items, key = { it.key }) { item ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .toggleable(item.key in state.selected, enabled = state.canChange, role = Role.Checkbox, onValueChange = { toggle(item.key) })
                        .padding(start = 8.dp, end = 4.dp).testTag("auto-task-import-row-${item.key}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.key in state.selected, onCheckedChange = null, enabled = state.canChange)
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(item.name.ifBlank { item.id }, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(when (item.status) {
                                AutoTaskImportStatus.New -> R.string.import_status_new
                                AutoTaskImportStatus.Update -> R.string.import_status_update
                                AutoTaskImportStatus.Exists -> R.string.import_status_exist
                            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { edit(item.key) }, enabled = state.canChange,
                            modifier = Modifier.testTag("auto-task-import-edit-${item.key}")) { Text(stringResource(R.string.edit)) }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (state.allSelected) clear() else all() }, enabled = state.canChange && state.items.isNotEmpty(),
                    modifier = Modifier.weight(1f).testTag("auto-task-import-all")) {
                    Text(stringResource(if (state.allSelected) R.string.select_cancel_count else R.string.select_all_count,
                        state.selectedCount, state.items.size))
                }
                TextButton(onClick = cancel, enabled = !state.busy, modifier = Modifier.testTag("auto-task-import-cancel")) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = confirm, enabled = state.canChange && state.items.isNotEmpty(), modifier = Modifier.testTag("auto-task-import-confirm")) { Text(stringResource(R.string.ok)) }
            }
        }
    }
}
