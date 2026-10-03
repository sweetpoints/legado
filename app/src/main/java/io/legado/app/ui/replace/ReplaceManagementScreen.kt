package io.legado.app.ui.replace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import kotlinx.coroutines.flow.distinctUntilChanged

class ReplaceManagementActions(val back: () -> Unit, val query: (String, Int, Int) -> Unit,
    val selected: (Long, Boolean) -> Unit, val all: () -> Unit, val invert: () -> Unit, val interval: () -> Unit,
    val enabled: (List<Long>, Boolean) -> Unit, val edge: (List<Long>, Boolean) -> Unit,
    val dialog: (ReplaceManagementDialog, List<Long>) -> Unit, val draft: (String, Int, Int) -> Unit,
    val cancelDialog: () -> Unit, val confirmDialog: () -> Unit, val manual: () -> Unit,
    val effect: (ReplaceManagementAction, Long?) -> Unit, val forgetImport: (String) -> Unit,
    val passphrase: () -> Unit, val copy: () -> Unit, val retry: () -> Unit,
    val beginSelection: () -> Boolean, val selectionRange: (Long, Long) -> Unit, val finishSelection: () -> Unit,
    val beginDrag: (Long) -> Boolean, val dragTo: (Long, Boolean) -> Unit, val finishDrag: () -> Unit,
    val cancelGesture: () -> Unit, val scroll: (Int, Int) -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceManagementScreen(state: ReplaceManagementState, actions: ReplaceManagementActions) {
    val focus = LocalFocusManager.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val list = rememberLazyListState()
    val gesture = rememberReplaceManagementGesture(list, actions)
    var restored by remember { mutableStateOf(false) }
    val currentScroll by rememberUpdatedState(actions.scroll)
    LaunchedEffect(state.loaded, state.rows.size) {
        if (!restored && state.loaded && state.rows.isNotEmpty()) {
            list.scrollToItem(state.scrollIndex.coerceIn(0, state.rows.lastIndex), state.scrollOffset)
            restored = true
        }
    }
    LaunchedEffect(list, restored) { if (!restored) return@LaunchedEffect; snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        .distinctUntilChanged().collect { currentScroll(it.first, it.second) } }
    var query by remember { mutableStateOf(TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd))) }
    LaunchedEffect(state.query, state.queryStart, state.queryEnd) {
        if (query.text != state.query || query.selection != TextRange(state.queryStart, state.queryEnd))
            query = TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd))
    }
    var mainMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<Long?>(null) }
    val enabled = state.loaded && !state.busy && state.pending == null
    val nativeEnabled = enabled && !state.waitingNative
    val selected = state.visibleSelection
    val enabledLabel = stringResource(R.string.enabled)
    val disabledLabel = stringResource(R.string.disabled)
    val noGroupLabel = stringResource(R.string.no_group)
    val moveUp = stringResource(R.string.to_top)
    val moveDown = stringResource(R.string.to_bottom)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))) {
            TopAppBar(title = {
                OutlinedTextField(query, { value -> query = value; actions.query(value.text, value.selection.start, value.selection.end) },
                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("replace-rule-search"),
                    placeholder = { Text(stringResource(R.string.replace_purify_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
            }, navigationIcon = { IconButton(actions.back, modifier = Modifier.testTag("replace-rule-back")) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            } }, actions = {
                Box {
                    IconButton({ groupMenu = true }, enabled = enabled, modifier = Modifier.testTag("replace-rule-groups")) {
                        Icon(painterResource(R.drawable.ic_groups), stringResource(R.string.menu_action_group))
                    }
                    DropdownMenu(groupMenu, { groupMenu = false }) {
                        ManageMenu(R.string.group_manage) { groupMenu = false; actions.effect(ReplaceManagementAction.Groups, null) }
                        listOf(enabledLabel, disabledLabel, noGroupLabel).forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { groupMenu = false; actions.query(label, label.length, label.length) })
                        }
                        state.groups.forEach { group -> DropdownMenuItem(text = { Text(group) }, onClick = {
                            groupMenu = false; val text = "group:$group"; actions.query(text, text.length, text.length)
                        }) }
                    }
                }
                Box {
                    IconButton({ mainMenu = true }, enabled = nativeEnabled, modifier = Modifier.testTag("replace-rule-more")) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu))
                    }
                    DropdownMenu(mainMenu, { mainMenu = false }) {
                        ManageMenu(R.string.add_replace_rule, "replace-rule-add", R.drawable.ic_add) { mainMenu = false; actions.effect(ReplaceManagementAction.Add, null) }
                        ManageMenu(R.string.import_local, "replace-rule-import-local", R.drawable.ic_import) { mainMenu = false; actions.effect(ReplaceManagementAction.ImportLocal, null) }
                        ManageMenu(R.string.import_on_line, "replace-rule-import-url", R.drawable.ic_import) { mainMenu = false; actions.dialog(ReplaceManagementDialog.ImportUrl, emptyList()) }
                        ManageMenu(R.string.import_by_qr_code, "replace-rule-import-qr", R.drawable.ic_import) { mainMenu = false; actions.effect(ReplaceManagementAction.ImportQr, null) }
                        DropdownMenuItem(text = { Text(stringResource(R.string.manual_replace_rule)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_find_replace), null) },
                            trailingIcon = { Checkbox(state.manual, null, modifier = Modifier.testTag("replace-rule-manual-state")) }, modifier = Modifier.testTag("replace-rule-manual"),
                            onClick = { mainMenu = false; actions.manual() })
                        ManageMenu(R.string.help, icon = R.drawable.ic_help) { mainMenu = false; actions.effect(ReplaceManagementAction.Help, null) }
                    }
                }
            })
            state.error?.let { error -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(actions.retry, enabled = !state.busy) { Text(stringResource(R.string.retry)) }
            } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("replace-rule-list")
                .replaceRuleSlideSelection(list, gesture, enabled, actions, rtl)) {
                itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                    Row(Modifier.fillMaxWidth()
                        .testTag("replace-rule-row-${row.id}").replaceRuleReorder(row.id, list, gesture, enabled, actions).semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(moveUp) {
                                    val target = state.rows.getOrNull(index - 1)
                                    if (target != null && actions.beginDrag(row.id)) { actions.dragTo(target.id, false); actions.finishDrag(); true } else false
                                }, CustomAccessibilityAction(moveDown) {
                                    val target = state.rows.getOrNull(index + 1)
                                    if (target != null && actions.beginDrag(row.id)) { actions.dragTo(target.id, true); actions.finishDrag(); true } else false
                                })
                        }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(row.id in state.selected, { actions.selected(row.id, it) }, enabled = enabled,
                            modifier = Modifier.testTag("replace-rule-select-${row.id}"))
                        Text(row.displayName, Modifier.weight(1f).clickable(enabled = enabled) { actions.selected(row.id, row.id !in state.selected) }, maxLines = 1)
                        Switch(row.enabled, { actions.enabled(listOf(row.id), it) }, enabled = enabled,
                            modifier = Modifier.testTag("replace-rule-enabled-${row.id}"))
                        IconButton({ actions.effect(ReplaceManagementAction.Edit, row.id) }, enabled = nativeEnabled,
                            modifier = Modifier.testTag("replace-rule-edit-${row.id}")) {
                            Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.edit))
                        }
                        Box {
                            IconButton({ rowMenu = row.id }, enabled = enabled, modifier = Modifier.testTag("replace-rule-menu-${row.id}")) {
                                Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu))
                            }
                            DropdownMenu(rowMenu == row.id, { rowMenu = null }) {
                                ManageMenu(R.string.to_top, "replace-rule-top-${row.id}") { rowMenu = null; actions.edge(listOf(row.id), true) }
                                ManageMenu(R.string.to_bottom, "replace-rule-bottom-${row.id}") { rowMenu = null; actions.edge(listOf(row.id), false) }
                                ManageMenu(R.string.delete, "replace-rule-delete-${row.id}", danger = true) { rowMenu = null; actions.dialog(ReplaceManagementDialog.Delete, listOf(row.id)) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${selected.size}/${state.rows.size}", Modifier.padding(horizontal = 12.dp).testTag("replace-rule-count"))
                TextButton({ if (selected.size == state.rows.size) actions.invert() else actions.all() }, enabled = enabled, modifier = Modifier.testTag("replace-rule-all")) { Text(stringResource(R.string.select_all)) }
                TextButton(actions.invert, enabled = enabled, modifier = Modifier.testTag("replace-rule-invert")) { Text(stringResource(R.string.revert_selection)) }
                TextButton({ actions.dialog(ReplaceManagementDialog.Delete, selected) }, enabled = enabled && selected.isNotEmpty(),
                    modifier = Modifier.testTag("replace-rule-delete-selection")) { Text(stringResource(R.string.delete)) }
                Box {
                    IconButton({ selectionMenu = true }, enabled = enabled, modifier = Modifier.testTag("replace-rule-selection-menu")) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.more_menu))
                    }
                    DropdownMenu(selectionMenu, { selectionMenu = false }) {
                        ManageMenu(R.string.enable_selection) { selectionMenu = false; actions.enabled(selected, true) }
                        ManageMenu(R.string.disable_selection) { selectionMenu = false; actions.enabled(selected, false) }
                        ManageMenu(R.string.add_group) { selectionMenu = false; actions.dialog(ReplaceManagementDialog.AddGroup, selected) }
                        ManageMenu(R.string.remove_group) { selectionMenu = false; actions.dialog(ReplaceManagementDialog.RemoveGroup, selected) }
                        ManageMenu(R.string.selection_to_top) { selectionMenu = false; actions.edge(selected, true) }
                        ManageMenu(R.string.selection_to_bottom) { selectionMenu = false; actions.edge(selected, false) }
                        ManageMenu(R.string.export_selection, "replace-rule-export") { selectionMenu = false; actions.effect(ReplaceManagementAction.Export, null) }
                        ManageMenu(R.string.share_selected_source, "replace-rule-share") { selectionMenu = false; actions.effect(ReplaceManagementAction.Share, null) }
                    }
                }
            }
        }
    }
    state.dialog?.let { dialog -> ManagementDialog(state, dialog, actions) }
}

@Composable private fun ManageMenu(label: Int, tag: String = "", icon: Int? = null, danger: Boolean = false, action: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
        leadingIcon = if (icon != null) { { Icon(painterResource(icon), null) } } else null,
        onClick = action, modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag))
}

@Composable private fun ManagementDialog(state: ReplaceManagementState, dialog: ReplaceManagementDialog, actions: ReplaceManagementActions) {
    var editor by remember(dialog) { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draftStart, state.draftEnd))) }
    LaunchedEffect(state.draft, state.draftStart, state.draftEnd) {
        if (editor.text != state.draft || editor.selection != TextRange(state.draftStart, state.draftEnd))
            editor = TextFieldValue(state.draft, TextRange(state.draftStart, state.draftEnd))
    }
    val output = dialog in listOf(ReplaceManagementDialog.ExportResult, ReplaceManagementDialog.Passphrase)
    val title = when (dialog) {
        ReplaceManagementDialog.Delete -> R.string.draw
        ReplaceManagementDialog.AddGroup -> R.string.add_group
        ReplaceManagementDialog.RemoveGroup -> R.string.remove_group
        ReplaceManagementDialog.ImportUrl -> R.string.import_on_line
        ReplaceManagementDialog.ExportResult -> R.string.export_success
        ReplaceManagementDialog.Passphrase -> R.string.shibboleth
    }
    AlertDialog(onDismissRequest = { if (!state.busy) actions.cancelDialog() }, title = { Text(stringResource(title)) }, text = {
        Column {
            if (dialog == ReplaceManagementDialog.Delete) {
                Text(stringResource(R.string.sure_del) + "\n" + state.targets.mapNotNull { id -> state.rows.firstOrNull { it.id == id }?.name }.joinToString("\n"))
            } else {
                if (dialog == ReplaceManagementDialog.ExportResult) state.feedback?.summary?.takeIf { it.isNotBlank() }?.let { Text(it) }
                OutlinedTextField(editor, { value -> editor = value; actions.draft(value.text, value.selection.start, value.selection.end) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("replace-rule-dialog-field"),
                    label = { Text(if (dialog == ReplaceManagementDialog.ImportUrl) "url" else stringResource(if (output) R.string.path else R.string.group_name)) })
                if (dialog == ReplaceManagementDialog.ImportUrl) LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(state.history) { _, value -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton({ actions.draft(value, value.length, value.length) }, modifier = Modifier.weight(1f)) { Text(value, maxLines = 1) }
                        IconButton({ actions.forgetImport(value) }, enabled = !state.busy) { Icon(painterResource(R.drawable.ic_outline_delete), stringResource(R.string.delete)) }
                    } }
                }
                if (dialog == ReplaceManagementDialog.AddGroup || dialog == ReplaceManagementDialog.RemoveGroup)
                    LazyColumn(Modifier.heightIn(max = 180.dp)) { itemsIndexed(state.groups) { _, group ->
                        TextButton({ actions.draft(group, group.length, group.length) }, enabled = !state.busy) { Text(group) }
                    } }
                if (dialog == ReplaceManagementDialog.ExportResult && state.feedback?.canEncode == true)
                    TextButton(actions.passphrase, enabled = !state.busy, modifier = Modifier.testTag("replace-rule-passphrase")) { Text(stringResource(R.string.shibboleth)) }
            }
        }
    }, confirmButton = { TextButton(if (output) actions.copy else actions.confirmDialog, enabled = !state.busy,
        modifier = Modifier.testTag("replace-rule-dialog-confirm")) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(actions.cancelDialog, enabled = !state.busy) { Text(stringResource(R.string.cancel)) } })
}
