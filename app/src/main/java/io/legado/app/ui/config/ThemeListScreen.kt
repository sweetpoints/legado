package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable internal fun ThemeListScreen(state: ThemeListState, apply: (String) -> Unit,
    share: (String) -> Unit, delete: (String) -> Unit, confirmDelete: () -> Unit,
    cancelDelete: () -> Unit, import: () -> Unit, reload: () -> Unit, close: () -> Unit,
    modifier: Modifier = Modifier) {
    Surface(modifier) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(close, Modifier.testTag("theme-list-close")) { Icon(painterResource(R.drawable.ic_baseline_close), stringResource(R.string.close)) }
                    Text(stringResource(R.string.theme_list), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    TextButton(import, Modifier.testTag("theme-list-import"), enabled = !state.loading && !state.busy && state.event == null,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) { Text("剪贴板导入") }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("theme-list-progress"))
            state.error?.let { message -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(reload, Modifier.testTag("theme-list-retry"), enabled = !state.busy) { Text(stringResource(R.string.retry)) }
            } }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("theme-list-items")) {
                items(state.items, key = { it.key }) { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme-list-apply-${item.key}")
                            .clickable(enabled = !state.busy && !state.loading && state.event == null) { apply(item.key) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ share(item.key) }, Modifier.testTag("theme-list-share-${item.key}"), enabled = !state.busy && !state.loading && state.event == null) {
                            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share))
                        }
                        IconButton({ delete(item.key) }, Modifier.testTag("theme-list-delete-${item.key}"), enabled = !state.busy && !state.loading && state.event == null) {
                            Icon(painterResource(R.drawable.ic_clear_all), stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    state.items.find { it.key == state.deleteKey }?.let { item ->
        AlertDialog(onDismissRequest = cancelDelete, title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + item.name) },
            confirmButton = { TextButton(confirmDelete, Modifier.testTag("theme-list-delete-confirm"), enabled = !state.busy) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(cancelDelete, Modifier.testTag("theme-list-delete-cancel")) { Text(stringResource(R.string.cancel)) } })
    }
}
