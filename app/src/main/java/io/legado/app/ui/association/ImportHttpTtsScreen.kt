package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.legado.app.data.repository.HttpTtsImportItem

internal fun httpTtsImportStatus(item: HttpTtsImportItem): Int = when {
    item.localUpdatedAt == null -> R.string.import_status_new
    item.updatedAt > item.localUpdatedAt -> R.string.import_status_update
    else -> R.string.import_status_exist
}

@Composable
internal fun ImportHttpTtsScreen(state: ImportHttpTtsState, onToggle: (String) -> Unit,
    onToggleAll: () -> Unit, onCode: (String) -> Unit, onConfirm: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = !state.loading && !state.busy && !state.finished
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
            .imePadding()) {
            Surface(color = MaterialTheme.colorScheme.primary) {
                Text(stringResource(R.string.import_tts), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("tts-import-progress"))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("tts-import-list")) {
                state.error?.let { error -> item {
                    Column(Modifier.padding(16.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        if (state.items.isEmpty()) TextButton(onRetry, enabled = !state.loading && !state.busy) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                } }
                if (!state.loading && state.items.isEmpty() && state.error == null) item {
                    Text(stringResource(R.string.wrong_format), Modifier.padding(16.dp))
                }
                items(state.items, key = { it.key }) { item ->
                    Row(Modifier.fillMaxWidth().testTag("tts-import-row-${item.key}")
                        .clickable(enabled = enabled, role = Role.Checkbox, onClick = { onToggle(item.key) })
                        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.key in state.selected, onCheckedChange = { onToggle(item.key) },
                            enabled = enabled, modifier = Modifier.testTag("tts-import-check-${item.key}"))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(httpTtsImportStatus(item)), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton({ onCode(item.key) }, enabled = enabled,
                            modifier = Modifier.testTag("tts-import-code-${item.key}")) { Text(stringResource(R.string.open)) }
                    }
                }
            }
            // Separate rows keep the selected count and 48dp actions reachable on narrow windows.
            TextButton(onToggleAll, enabled = enabled && state.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("tts-import-select-all")) {
                Text(stringResource(if (state.isSelectAll) R.string.select_cancel_count else R.string.select_all_count,
                    state.selectCount, state.items.size))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, enabled = !state.busy, modifier = Modifier.testTag("tts-import-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onConfirm, enabled = enabled && (state.error == null || state.items.isNotEmpty()),
                    modifier = Modifier.testTag("tts-import-confirm")) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}
