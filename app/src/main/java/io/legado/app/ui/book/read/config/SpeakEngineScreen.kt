package io.legado.app.ui.book.read.config

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.SpeakSystemEngine
import io.legado.app.help.SourceSharePassphrase

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SpeakEngineScreen(
    state: SpeakEngineUiState,
    onSystem: (SpeakSystemEngine) -> Unit,
    onHttp: (Long) -> Unit,
    onLogin: (Long) -> Unit,
    onEdit: (Long?) -> Unit,
    onDelete: (Long?) -> Unit,
    onConfirmDelete: () -> Unit,
    onDefault: () -> Unit,
    onClear: () -> Unit,
    onLocal: () -> Unit,
    onOnline: (Boolean) -> Unit,
    onInput: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onImport: () -> Unit,
    onExport: (Boolean) -> Unit,
    onApply: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onPassphrase: () -> Unit,
    onCopy: (String) -> Unit,
    onCloseShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.speak_engine),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onClear) { Text(stringResource(R.string.clear)) }
                TextButton(onClick = { onEdit(null) }) { Text(stringResource(R.string.add)) }
                Box {
                    TextButton(
                        onClick = { menu = true },
                        modifier = Modifier.testTag("speak-engine-menu"),
                    ) {
                        Text("⋮")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        listOf(
                                R.string.import_default_rule to onDefault,
                                R.string.import_local to onLocal,
                                R.string.import_on_line to { onOnline(true) },
                                R.string.export_all to { onExport(true) },
                                R.string.export to { onExport(false) },
                            )
                            .forEach { (title, action) ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(title)) },
                                    onClick = {
                                        menu = false
                                        action()
                                    },
                                )
                            }
                    }
                }
            }
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                val systems = listOf(SpeakSystemEngine("", "系统默认")) + state.systems
                items(systems, key = { "system:${it.name}" }) { engine ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("speak-system:${engine.name}")
                            .semantics { selected = state.systemName == engine.name }
                            .combinedClickable(
                                role = Role.RadioButton,
                                enabled = !state.busy && !state.finished,
                                onClick = { onSystem(engine) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(state.systemName == engine.name, null, Modifier.padding(8.dp))
                        Text(engine.label, Modifier.weight(1f).padding(vertical = 12.dp))
                        Text(
                            "SYS",
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                items(state.engines, key = { "http:${it.id}" }) { engine ->
                    Row(
                        Modifier.fillMaxWidth().testTag("speak-http:${engine.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f)
                                .testTag("speak-select:${engine.id}")
                                .semantics { selected = state.selection == engine.id.toString() }
                                .combinedClickable(
                                    role = Role.RadioButton,
                                    enabled = !state.busy && !state.finished,
                                    onClick = { onHttp(engine.id) },
                                    onLongClick = { onLogin(engine.id) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                state.selection == engine.id.toString(),
                                null,
                                Modifier.padding(8.dp),
                            )
                            Text(
                                engine.name,
                                Modifier.testTag("speak-name:${engine.id}")
                                    .padding(vertical = 12.dp),
                            )
                        }
                        TextButton(
                            { onEdit(engine.id) },
                            modifier = Modifier.widthIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.edit))
                        }
                        TextButton(
                            { onDelete(engine.id) },
                            modifier =
                                Modifier.widthIn(min = 48.dp).testTag("speak-delete:${engine.id}"),
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton({ onApply(false) }, enabled = !state.busy && !state.finished) {
                    Text(stringResource(R.string.book))
                }
                TextButton({ onApply(true) }, enabled = !state.busy && !state.finished) {
                    Text(stringResource(R.string.general))
                }
                TextButton(onCancel, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
    state.deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { onDelete(null) },
            title = { Text(stringResource(R.string.draw)) },
            text = {
                Text(
                    stringResource(R.string.sure_del) +
                        "\n" +
                        state.engines.find { it.id == id }?.name.orEmpty()
                )
            },
            confirmButton = { TextButton(onConfirmDelete) { Text(stringResource(R.string.yes)) } },
            dismissButton = {
                TextButton({ onDelete(null) }) { Text(stringResource(R.string.no)) }
            },
        )
    }
    if (state.online)
        AlertDialog(
            onDismissRequest = { onOnline(false) },
            title = { Text(stringResource(R.string.import_on_line)) },
            text = {
                Column {
                    OutlinedTextField(
                        state.input,
                        onInput,
                        label = { Text("url") },
                        modifier = Modifier.testTag("speak-import-input"),
                    )
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(
                            state.histories.filter { it.contains(state.input, ignoreCase = true) }
                        ) { url ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ onInput(url) }, Modifier.weight(1f)) { Text(url) }
                                TextButton({ onHistoryDelete(url) }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onImport) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                TextButton({ onOnline(false) }) { Text(stringResource(R.string.cancel)) }
            },
        )
    state.share?.let { share ->
        AlertDialog(
            onDismissRequest = onCloseShare,
            title = {
                Text(
                    stringResource(
                        if (share.passphrase == null) R.string.export_success
                        else R.string.shibboleth
                    )
                )
            },
            text = {
                Column {
                    if (share.summary.isNotEmpty() && share.passphrase == null) Text(share.summary)
                    OutlinedTextField(
                        share.passphrase ?: share.url,
                        {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.path)) },
                    )
                }
            },
            confirmButton = {
                TextButton({
                    onCopy(share.passphrase ?: share.url)
                    onCloseShare()
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                if (share.passphrase == null && SourceSharePassphrase.canEncode(share.url))
                    TextButton(onPassphrase) { Text(stringResource(R.string.shibboleth)) }
            },
        )
    }
}
