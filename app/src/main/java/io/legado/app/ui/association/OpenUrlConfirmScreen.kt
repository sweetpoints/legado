package io.legado.app.ui.association

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun OpenUrlConfirmScreen(
    state: OpenUrlConfirmUiState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onDisableSource: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.isWorking && !state.shouldClose
    Surface(modifier, shape = MaterialTheme.shapes.large) {
        Column {
            LegadoTopAppBar(
                title = stringResource(R.string.open_url_confirm_title),
                onBack = onClose,
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("open-url-menu"),
                        ) {
                            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu))
                        }
                        DropdownMenu(menuExpanded && enabled, { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.disable_source)) },
                                onClick = { menuExpanded = false; onDisableSource() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_source)) },
                                onClick = { menuExpanded = false; onRequestDelete() },
                            )
                        }
                    }
                },
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.sourceName.isNotBlank()) Text(state.sourceName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.open_url_confirm_message))
                if (state.isWorking) CircularProgressIndicator(Modifier.testTag("open-url-progress"))
                state.error?.let {
                    Text(stringResource(R.string.error) + ": " + it, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("open-url-error"))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose, modifier = Modifier.testTag("open-url-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(onClick = onOpen, enabled = enabled && state.uri.isNotBlank(),
                    modifier = Modifier.testTag("open-url-confirm")) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
    if (state.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_del) + "\n" + state.sourceName) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = enabled,
                    modifier = Modifier.testTag("open-url-delete-confirm")) { Text(stringResource(R.string.yes)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.no)) }
            },
        )
    }
}
