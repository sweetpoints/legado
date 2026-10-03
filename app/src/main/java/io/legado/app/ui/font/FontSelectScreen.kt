package io.legado.app.ui.font

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.FontEntry
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun FontSelectScreen(
    state: FontSelectUiState,
    selectedPath: String,
    onSelect: (String) -> Unit,
    onDefault: () -> Unit,
    onFolder: () -> Unit,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onSystemTypeface: (Int) -> Unit,
    onCancelSystem: () -> Unit,
    onClose: () -> Unit,
    preview: @Composable (FontEntry, String, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            LegadoTopAppBar(
                stringResource(R.string.select_font),
                onClose,
                windowInsets = WindowInsets(0, 0, 0, 0),
                actions = {
                    Box {
                        IconButton({ menuExpanded = true }, Modifier.testTag("font-actions")) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(menuExpanded, { menuExpanded = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.default_font)) },
                                {
                                    menuExpanded = false
                                    onDefault()
                                },
                                modifier = Modifier.testTag("font-default"),
                                enabled = !state.importing,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.other_folder)) },
                                {
                                    menuExpanded = false
                                    onFolder()
                                },
                                modifier = Modifier.testTag("font-folder"),
                                enabled = !state.importing,
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.import_str)) },
                                {
                                    menuExpanded = false
                                    onImport()
                                },
                                modifier = Modifier.testTag("font-import"),
                                enabled = !state.importing,
                            )
                        }
                    }
                },
            )
            if (state.loading || state.importing)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("font-progress"))
            if (state.importSucceeded)
                Text(stringResource(R.string.success), Modifier.padding(12.dp))
            state.error?.let {
                Text(
                    if (state.invalidImport) stringResource(R.string.wrong_format) else it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp).testTag("font-error"),
                )
                TextButton(onRetry, enabled = !state.loading && !state.importing) {
                    Text(stringResource(R.string.retry))
                }
            }
            if (!state.loading && state.entries.isEmpty())
                Text(stringResource(R.string.empty), Modifier.padding(16.dp).testTag("font-empty"))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).testTag("font-list"),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(state.entries, key = { it.path }) { entry ->
                    val selected = entry.path == selectedPath
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        border =
                            if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else null,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("font-entry-${entry.path}")
                                .selectable(
                                    selected,
                                    enabled = !state.loading && !state.importing,
                                    role = Role.RadioButton,
                                ) {
                                    onSelect(entry.path)
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            preview(
                                entry,
                                stringResource(
                                    if (entry.privateFolder) R.string.font_item_private
                                    else R.string.font_item_external,
                                    entry.name,
                                ),
                                Modifier.weight(1f),
                            )
                            if (selected)
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = null,
                                )
                        }
                    }
                }
            }
        }
    }
    if (state.systemPicker) {
        val options = stringArrayResource(R.array.system_typefaces)
        AlertDialog(
            onDismissRequest = onCancelSystem,
            title = { Text(stringResource(R.string.system_typeface)) },
            text = {
                Column {
                    options.forEachIndexed { index, label ->
                        TextButton(
                            { onSystemTypeface(index) },
                            Modifier.fillMaxWidth().testTag("font-system-$index"),
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onCancelSystem) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
