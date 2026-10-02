package io.legado.app.ui.book.import.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable internal fun ServersScreen(state: ServersUiState, onChoose: (Long) -> Unit, onAdd: () -> Unit,
    onEdit: (Long) -> Unit, onDeleteRequest: (Long?) -> Unit, onDelete: () -> Unit,
    onApply: () -> Unit, onDefault: () -> Unit, onClose: () -> Unit, onRetry: () -> Unit) {
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.server_config), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onAdd, Modifier.testTag("servers-add")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.add)) }
            }
            if (state.loading || state.deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onRetry, Modifier.testTag("servers-retry")) { Text(stringResource(R.string.retry)) } }
            LazyColumn(Modifier.weight(1f).testTag("servers-list")) {
                items(state.rows, key = { it.id }) { server ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("servers-row-${server.id}")
                        .selectable(server.id == state.selected, role = Role.RadioButton, onClick = { onChoose(server.id) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(server.id == state.selected, onClick = null)
                        Text(server.name, Modifier.weight(1f))
                        IconButton({ onEdit(server.id) }, Modifier.testTag("servers-edit-${server.id}")) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit)) }
                        IconButton({ onDeleteRequest(server.id) }, enabled = !state.deleting, modifier = Modifier.testTag("servers-delete-${server.id}")) {
                            Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onDefault, Modifier.testTag("servers-default")) { Text(stringResource(R.string.text_default)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClose, Modifier.testTag("servers-cancel")) { Text(stringResource(R.string.cancel)) }
                TextButton(onApply, Modifier.testTag("servers-apply")) { Text(stringResource(R.string.ok)) }
            }
        }
    }
    state.deleteId?.let { id -> AlertDialog(onDismissRequest = { onDeleteRequest(null) },
        title = { Text(stringResource(R.string.draw)) }, text = { Text(stringResource(R.string.sure_del)) },
        confirmButton = { TextButton(onDelete, Modifier.testTag("servers-confirm-delete-$id")) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton({ onDeleteRequest(null) }) { Text(stringResource(R.string.no)) } }) }
}
