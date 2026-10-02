package io.legado.app.ui.book.toc.rule

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

class TxtTocManagementActions(
    val back: () -> Unit, val add: () -> Unit, val edit: (Long) -> Unit,
    val importFile: () -> Unit, val importQr: () -> Unit, val help: () -> Unit,
    val toggle: (Long) -> Unit, val selectAll: () -> Unit, val invert: () -> Unit,
    val setEnabled: (Long, Boolean) -> Unit, val enableSelection: (Boolean) -> Unit,
    val requestDelete: (List<Long>) -> Unit, val confirmDelete: () -> Unit, val deleteSelection: () -> Unit,
    val share: () -> Unit, val export: () -> Unit, val importDefault: () -> Unit,
    val showOnline: (Boolean) -> Unit, val inputOnline: (String) -> Unit, val deleteHistory: (String) -> Unit, val confirmOnline: () -> Unit,
    val closeExport: () -> Unit, val copyExport: () -> Unit, val createPassphrase: () -> Unit,
    val closePassphrase: () -> Unit, val copyPassphrase: () -> Unit,
    val beginSlide: (Long) -> Unit, val slideTo: (Long) -> Unit, val endSlide: () -> Unit,
    val move: (Long, Long) -> Unit, val finishReorder: () -> Unit, val retry: () -> Unit,
    val cancelSlide: () -> Unit, val cancelReorder: () -> Unit,
    val cancelDelete: () -> Unit, val inputQuery: (String) -> Unit, val toEdge: (Long, Boolean) -> Unit,
    val choose: (Long) -> Unit, val confirmChoice: () -> Unit,
)

@Composable
fun TxtTocRuleManagementScreen(state: TxtTocRuleManagementUiState, actions: TxtTocManagementActions, modifier: Modifier = Modifier, picker: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // Gesture blocks remain stable through selection/order recomposition.
    val latest by rememberUpdatedState(actions)
    val stable = remember { TxtTocManagementActions(
        { latest.back() }, { latest.add() }, { latest.edit(it) }, { latest.importFile() }, { latest.importQr() }, { latest.help() },
        { latest.toggle(it) }, { latest.selectAll() }, { latest.invert() }, { a,b -> latest.setEnabled(a,b) }, { latest.enableSelection(it) },
        { latest.requestDelete(it) }, { latest.confirmDelete() }, { latest.deleteSelection() }, { latest.share() }, { latest.export() }, { latest.importDefault() },
        { latest.showOnline(it) }, { latest.inputOnline(it) }, { latest.deleteHistory(it) }, { latest.confirmOnline() },
        { latest.closeExport() }, { latest.copyExport() }, { latest.createPassphrase() }, { latest.closePassphrase() }, { latest.copyPassphrase() },
        { latest.beginSlide(it) }, { latest.slideTo(it) }, { latest.endSlide() }, { a,b -> latest.move(a,b) }, { latest.finishReorder() }, { latest.retry() }, { latest.cancelSlide() }, { latest.cancelReorder() }, { latest.cancelDelete() }, { latest.inputQuery(it) }, { a,b -> latest.toEdge(a,b) }, { latest.choose(it) }, { latest.confirmChoice() }) }
    val drag = rememberTxtTocListDrag(list, stable)
    val up = stringResource(R.string.txt_toc_move_up)
    val down = stringResource(R.string.txt_toc_move_down)
    Surface(modifier.fillMaxSize()) {
        Scaffold(topBar = {
            LegadoTopAppBar(stringResource(R.string.txt_toc_rule), actions.back, actions = {
                IconButton(actions.add, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-add")) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.create)) }
                Box {
                    IconButton({ menu = true }, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-menu")) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu)) }
                    DropdownMenu(menu, { menu = false }) {
                        TxtTocMenuItem(R.string.import_local) { menu = false; actions.importFile() }
                        TxtTocMenuItem(R.string.import_on_line) { menu = false; actions.showOnline(true) }
                        TxtTocMenuItem(R.string.import_by_qr_code) { menu = false; actions.importQr() }
                        TxtTocMenuItem(R.string.import_default_rule) { menu = false; actions.importDefault() }
                        TxtTocMenuItem(R.string.help) { menu = false; actions.help() }
                    }
                }
            })
        }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    if (picker) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(actions.back, modifier = Modifier.testTag("txt-toc-cancel")) { Text(stringResource(R.string.cancel)) }
                        TextButton(actions.confirmChoice, enabled = !state.busy && !state.pickerFinished, modifier = Modifier.testTag("txt-toc-confirm")) { Text(stringResource(R.string.ok)) }
                    } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.allSelected, { actions.selectAll() }, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-all"))
                        Text(stringResource(if (state.allSelected) R.string.select_cancel_count else R.string.select_all_count, state.selection.size, state.visible.size), modifier = Modifier.weight(1f))
                        TextButton(actions.invert, enabled = !state.busy) { Text(stringResource(R.string.revert_selection)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(actions.deleteSelection, enabled = !state.busy && state.selection.isNotEmpty(), modifier = Modifier.testTag("txt-toc-delete-selection")) { Text(stringResource(R.string.delete)) }
                        Box {
                            TextButton({ selectionMenu = true }, enabled = !state.busy && state.selection.isNotEmpty(), modifier = Modifier.testTag("txt-toc-selection-menu")) { Text(stringResource(R.string.menu)) }
                            DropdownMenu(selectionMenu, { selectionMenu = false }) {
                                TxtTocMenuItem(R.string.enable_selection) { selectionMenu = false; actions.enableSelection(true) }
                                TxtTocMenuItem(R.string.disable_selection) { selectionMenu = false; actions.enableSelection(false) }
                                TxtTocMenuItem(R.string.export_selection, "txt-toc-export") { selectionMenu = false; actions.export() }
                                TxtTocMenuItem(R.string.share_selected_source, "txt-toc-share") { selectionMenu = false; actions.share() }
                            }
                        }
                    }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.padding(padding).imePadding()) {
                OutlinedTextField(state.query, actions.inputQuery, singleLine = true,
                    label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("txt-toc-search"))
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { error -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(12.dp))
                    TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
                } }
                Box(Modifier.weight(1f)) {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("txt-toc-list")
                    .txtTocSlideSelection(list, drag, !picker && !state.busy, stable)) {
                    itemsIndexed(state.visible, key = { _, rule -> rule.id }) { index, rule ->
                        Column(Modifier.fillMaxWidth().testTag("txt-toc-row-${rule.id}")) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (picker) RadioButton(rule.id == state.selectedId, { actions.choose(rule.id) }, enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = rule.name }.testTag("txt-toc-select-${rule.id}"))
                                else Checkbox(rule.id in state.selected, { actions.toggle(rule.id) }, enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = rule.name }.testTag("txt-toc-select-${rule.id}"))
                                Text(rule.name, modifier = Modifier.weight(1f).padding(vertical = 16.dp)
                                    .txtTocReorder(rule.id, list, drag, !state.busy && state.query.isBlank(), stable)
                                    .clickable(enabled = !state.busy) { if (picker) actions.choose(rule.id) else actions.toggle(rule.id) }
                                    .semantics { customActions = buildList {
                                        if (!state.busy && state.query.isBlank() && index > 0) add(CustomAccessibilityAction(up) { actions.move(rule.id, state.visible[index - 1].id); actions.finishReorder(); true })
                                        if (!state.busy && state.query.isBlank() && index < state.visible.lastIndex) add(CustomAccessibilityAction(down) { actions.move(rule.id, state.visible[index + 1].id); actions.finishReorder(); true })
                                    } }.testTag("txt-toc-name-${rule.id}"))
                                Switch(rule.enable, { actions.setEnabled(rule.id, it) }, enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = rule.name }.testTag("txt-toc-enabled-${rule.id}"))
                                IconButton({ actions.edit(rule.id) }, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-edit-${rule.id}")) { Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit)) }
                                if (picker) IconButton({ actions.requestDelete(listOf(rule.id)) }, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-delete-${rule.id}")) { Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete)) }
                                else {
                                    var rowMenu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton({ rowMenu = true }, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-row-menu-${rule.id}")) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu)) }
                                        DropdownMenu(rowMenu, { rowMenu = false }) {
                                            TxtTocMenuItem(R.string.to_top, "txt-toc-top-${rule.id}") { rowMenu = false; if (rule.id in state.selected) actions.toggle(rule.id); actions.toEdge(rule.id, true) }
                                            TxtTocMenuItem(R.string.to_bottom, "txt-toc-bottom-${rule.id}") { rowMenu = false; if (rule.id in state.selected) actions.toggle(rule.id); actions.toEdge(rule.id, false) }
                                            TxtTocMenuItem(R.string.delete, "txt-toc-delete-${rule.id}") { rowMenu = false; if (rule.id in state.selected) actions.toggle(rule.id); actions.requestDelete(listOf(rule.id)) }
                                        }
                                    }
                                }
                            }
                            rule.example?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 48.dp, end = 12.dp, bottom = 8.dp).testTag("txt-toc-example-${rule.id}")) }
                        }
                        HorizontalDivider()
                    }
                }
                TxtTocFastScroll(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
    }
    if (state.deleteIds.isNotEmpty()) AlertDialog(onDismissRequest = actions.cancelDelete,
        title = { Text(stringResource(R.string.draw)) }, text = { Text(stringResource(R.string.sure_del) + "\n" + state.rules.filter { it.id in state.deleteIds }.joinToString("\n") { it.name }) },
        confirmButton = { TextButton(actions.confirmDelete, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-delete-confirm")) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(actions.cancelDelete, modifier = Modifier.testTag("txt-toc-delete-cancel")) { Text(stringResource(R.string.no)) } })
    if (state.online) AlertDialog(onDismissRequest = { actions.showOnline(false) }, title = { Text(stringResource(R.string.import_on_line)) },
        text = { Column(Modifier.imePadding()) {
            OutlinedTextField(state.onlineInput, actions.inputOnline, label = { Text(stringResource(R.string.source_url)) }, modifier = Modifier.fillMaxWidth().testTag("txt-toc-online-input"), enabled = !state.busy)
            LazyColumn(Modifier.heightIn(max = 180.dp)) { items(state.history.filter { state.onlineInput.isEmpty() || it.contains(state.onlineInput, true) }, key = { it }) { url ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ actions.inputOnline(url) }, modifier = Modifier.weight(1f)) { Text(url) }
                    IconButton({ actions.deleteHistory(url) }, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-history-delete-$url")) { Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete)) }
                }
            } }
        } }, confirmButton = { TextButton(actions.confirmOnline, enabled = !state.busy, modifier = Modifier.testTag("txt-toc-online-confirm")) { Text(stringResource(R.string.ok)) } },
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
private fun TxtTocMenuItem(label: Int, tag: String = "", onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick, modifier = Modifier.testTag(tag))
}
