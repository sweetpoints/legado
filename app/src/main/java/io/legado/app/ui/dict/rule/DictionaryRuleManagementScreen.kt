package io.legado.app.ui.dict.rule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.SourceSharePassphrase
import io.legado.app.ui.components.LegadoTopAppBar

class DictionaryManagementActions(
    val back: () -> Unit, val add: () -> Unit, val edit: (String) -> Unit,
    val importFile: () -> Unit, val importQr: () -> Unit, val help: () -> Unit,
    val toggle: (String) -> Unit, val selectAll: () -> Unit, val invert: () -> Unit,
    val setEnabled: (String, Boolean) -> Unit, val enableSelection: (Boolean) -> Unit,
    val requestDelete: (String?) -> Unit, val confirmDelete: () -> Unit, val deleteSelection: () -> Unit,
    val share: () -> Unit, val export: () -> Unit, val importDefault: () -> Unit,
    val showOnline: (Boolean) -> Unit, val inputOnline: (String) -> Unit, val deleteHistory: (String) -> Unit, val confirmOnline: () -> Unit,
    val closeExport: () -> Unit, val copyExport: () -> Unit, val createPassphrase: () -> Unit,
    val closePassphrase: () -> Unit, val copyPassphrase: () -> Unit,
    val beginSlide: (String) -> Unit, val slideTo: (String) -> Unit, val endSlide: () -> Unit,
    val move: (String, String) -> Unit, val finishReorder: () -> Unit, val retry: () -> Unit,
    val cancelSlide: () -> Unit, val cancelReorder: () -> Unit,
)

@Composable
fun DictionaryRuleManagementScreen(state: DictionaryRuleManagementUiState, actions: DictionaryManagementActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // Gesture blocks remain stable through selection/order recomposition.
    val latest by rememberUpdatedState(actions)
    val stable = remember { DictionaryManagementActions(
        { latest.back() }, { latest.add() }, { latest.edit(it) }, { latest.importFile() }, { latest.importQr() }, { latest.help() },
        { latest.toggle(it) }, { latest.selectAll() }, { latest.invert() }, { a,b -> latest.setEnabled(a,b) }, { latest.enableSelection(it) },
        { latest.requestDelete(it) }, { latest.confirmDelete() }, { latest.deleteSelection() }, { latest.share() }, { latest.export() }, { latest.importDefault() },
        { latest.showOnline(it) }, { latest.inputOnline(it) }, { latest.deleteHistory(it) }, { latest.confirmOnline() },
        { latest.closeExport() }, { latest.copyExport() }, { latest.createPassphrase() }, { latest.closePassphrase() }, { latest.copyPassphrase() },
        { latest.beginSlide(it) }, { latest.slideTo(it) }, { latest.endSlide() }, { a,b -> latest.move(a,b) }, { latest.finishReorder() }, { latest.retry() }, { latest.cancelSlide() }, { latest.cancelReorder() }) }
    val drag = rememberDictionaryListDrag(list, stable)
    val up = stringResource(R.string.dictionary_move_up)
    val down = stringResource(R.string.dictionary_move_down)
    Surface(modifier.fillMaxSize()) {
        Scaffold(topBar = {
            LegadoTopAppBar(stringResource(R.string.dict_rule), actions.back, actions = {
                IconButton(actions.add, enabled = !state.busy, modifier = Modifier.testTag("dictionary-add")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.create)) }
                Box {
                    IconButton({ menu = true }, enabled = !state.busy, modifier = Modifier.testTag("dictionary-menu")) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu)) }
                    DropdownMenu(menu, { menu = false }) {
                        DictionaryMenuItem(R.string.import_local) { menu = false; actions.importFile() }
                        DictionaryMenuItem(R.string.import_on_line) { menu = false; actions.showOnline(true) }
                        DictionaryMenuItem(R.string.import_by_qr_code) { menu = false; actions.importQr() }
                        DictionaryMenuItem(R.string.import_default_rule) { menu = false; actions.importDefault() }
                        DictionaryMenuItem(R.string.help) { menu = false; actions.help() }
                    }
                }
            })
        }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.allSelected, { actions.selectAll() }, enabled = !state.busy, modifier = Modifier.testTag("dictionary-all"))
                        Text(stringResource(if (state.allSelected) R.string.select_cancel_count else R.string.select_all_count, state.selection.size, state.rules.size), modifier = Modifier.weight(1f))
                        TextButton(actions.invert, enabled = !state.busy) { Text(stringResource(R.string.revert_selection)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(actions.deleteSelection, enabled = !state.busy && state.selection.isNotEmpty(), modifier = Modifier.testTag("dictionary-delete-selection")) { Text(stringResource(R.string.delete)) }
                        Box {
                            TextButton({ selectionMenu = true }, enabled = !state.busy && state.selection.isNotEmpty(), modifier = Modifier.testTag("dictionary-selection-menu")) { Text(stringResource(R.string.menu)) }
                            DropdownMenu(selectionMenu, { selectionMenu = false }) {
                                DictionaryMenuItem(R.string.enable_selection) { selectionMenu = false; actions.enableSelection(true) }
                                DictionaryMenuItem(R.string.disable_selection) { selectionMenu = false; actions.enableSelection(false) }
                                DictionaryMenuItem(R.string.export_selection, "dictionary-export") { selectionMenu = false; actions.export() }
                                DictionaryMenuItem(R.string.share_selected_source, "dictionary-share") { selectionMenu = false; actions.share() }
                            }
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding)) {
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(12.dp))
                    TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
                } }
                Box(Modifier.weight(1f)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("dictionary-list")
                    .dictionarySlideSelection(list, drag, !state.busy, stable)) {
                    itemsIndexed(state.rules, key = { _, rule -> rule.name }) { index, rule ->
                        Row(Modifier.fillMaxWidth().testTag("dictionary-row-${rule.name}"), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rule.name in state.selected, { actions.toggle(rule.name) }, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = rule.name }.testTag("dictionary-select-${rule.name}"))
                            Text(rule.name, modifier = Modifier.weight(1f).padding(vertical = 16.dp)
                                .dictionaryReorder(rule.name, list, drag, !state.busy, stable)
                                .clickable(enabled = !state.busy) { actions.toggle(rule.name) }
                                .semantics { customActions = buildList {
                                    if (!state.busy && index > 0) add(CustomAccessibilityAction(up) { actions.move(rule.name, state.rules[index - 1].name); actions.finishReorder(); true })
                                    if (!state.busy && index < state.rules.lastIndex) add(CustomAccessibilityAction(down) { actions.move(rule.name, state.rules[index + 1].name); actions.finishReorder(); true })
                                } }.testTag("dictionary-name-${rule.name}"))
                            Switch(rule.enabled, { actions.setEnabled(rule.name, it) }, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = rule.name }.testTag("dictionary-enabled-${rule.name}"))
                            IconButton({ actions.edit(rule.name) }, enabled = !state.busy, modifier = Modifier.testTag("dictionary-edit-${rule.name}")) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit)) }
                            IconButton({ actions.requestDelete(rule.name) }, enabled = !state.busy, modifier = Modifier.testTag("dictionary-delete-${rule.name}")) { Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete)) }
                        }
                        HorizontalDivider()
                    }
                }
                DictionaryFastScroll(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
    state.deleteName?.let { name -> AlertDialog(onDismissRequest = { actions.requestDelete(null) },
        title = { Text(stringResource(R.string.draw)) }, text = { Text(stringResource(R.string.sure_del) + "\n" + name) },
        confirmButton = { TextButton(actions.confirmDelete) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton({ actions.requestDelete(null) }) { Text(stringResource(R.string.no)) } }) }
    if (state.online) AlertDialog(onDismissRequest = { actions.showOnline(false) }, title = { Text(stringResource(R.string.import_on_line)) },
        text = { Column(Modifier.imePadding()) {
            OutlinedTextField(state.onlineInput, actions.inputOnline, label = { Text(stringResource(R.string.source_url)) }, modifier = Modifier.fillMaxWidth().testTag("dictionary-online-input"), enabled = !state.busy)
            LazyColumn(Modifier.heightIn(max = 180.dp)) { items(state.history.filter { state.onlineInput.isEmpty() || it.contains(state.onlineInput, true) }, key = { it }) { url ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ actions.inputOnline(url) }, modifier = Modifier.weight(1f)) { Text(url) }
                    IconButton({ actions.deleteHistory(url) }, enabled = !state.busy) { Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete)) }
                }
            } }
        } }, confirmButton = { TextButton(actions.confirmOnline, enabled = !state.busy, modifier = Modifier.testTag("dictionary-online-confirm")) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton({ actions.showOnline(false) }) { Text(stringResource(R.string.cancel)) } })
    state.exportUrl?.let { url ->
        var displayed by remember(url) { mutableStateOf(url) }
        AlertDialog(onDismissRequest = actions.closeExport, title = { Text(stringResource(R.string.export_success)) }, text = { Column {
            if (state.exportSummary.isNotEmpty()) Text(state.exportSummary)
            OutlinedTextField(displayed, { displayed = it }, label = { Text(stringResource(R.string.path)) })
        } }, confirmButton = { TextButton(actions.copyExport) { Text(stringResource(R.string.ok)) } },
            dismissButton = { Row {
                if (SourceSharePassphrase.canEncode(url)) TextButton(actions.createPassphrase, enabled = !state.busy) { Text(stringResource(R.string.shibboleth)) }
                TextButton(actions.closeExport) { Text(stringResource(R.string.cancel)) }
            } })
    }
    state.passphrase?.let { phrase -> AlertDialog(onDismissRequest = actions.closePassphrase,
        title = { Text(stringResource(R.string.shibboleth)) }, text = { SelectionContainer { Text(phrase) } },
        confirmButton = { TextButton(actions.copyPassphrase) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(actions.closePassphrase) { Text(stringResource(R.string.cancel)) } }) }
}
@Composable
private fun DictionaryMenuItem(label: Int, tag: String = "", onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, modifier = Modifier.testTag(tag))
}
