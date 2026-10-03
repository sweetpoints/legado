package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
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

class RssSourceManagementActions(
    val back: () -> Unit,
    val query: (String, Int, Int) -> Unit,
    val selected: (String, Boolean) -> Unit,
    val all: () -> Unit,
    val invert: () -> Unit,
    val interval: () -> Unit,
    val enabled: (List<String>, Boolean) -> Unit,
    val edge: (List<String>, Boolean) -> Unit,
    val dialog: (RssSourceManagementDialog, List<String>) -> Unit,
    val draft: (String, Int, Int) -> Unit,
    val cancelDialog: () -> Unit,
    val confirmDialog: () -> Unit,
    val defaults: () -> Unit,
    val effect: (RssSourceManagementAction, String?) -> Unit,
    val forgetImport: (String) -> Unit,
    val passphrase: () -> Unit,
    val copy: () -> Unit,
    val retry: () -> Unit,
    val beginSelection: () -> Boolean,
    val selectionRange: (String, String) -> Unit,
    val finishSelection: () -> Unit,
    val beginDrag: (String) -> Boolean,
    val dragTo: (String, Boolean) -> Unit,
    val finishDrag: () -> Unit,
    val cancelGesture: () -> Unit,
    val scroll: (Int, Int) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssSourceManagementScreen(
    state: RssSourceManagementState,
    actions: RssSourceManagementActions,
) {
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val gesture = rememberRssSourceManagementGesture(list, actions)
    var restored by remember { mutableStateOf(false) }
    val currentScroll by rememberUpdatedState(actions.scroll)
    LaunchedEffect(state.loaded, state.rows.size) {
        if (!restored && state.loaded && state.rows.isNotEmpty()) {
            list.scrollToItem(
                state.scrollIndex.coerceIn(0, state.rows.lastIndex),
                state.scrollOffset,
            )
            restored = true
        }
    }
    LaunchedEffect(list, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { currentScroll(it.first, it.second) }
    }
    var query by remember {
        mutableStateOf(TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd)))
    }
    LaunchedEffect(state.query, state.queryStart, state.queryEnd) {
        if (
            query.text != state.query ||
                query.selection != TextRange(state.queryStart, state.queryEnd)
        )
            query = TextFieldValue(state.query, TextRange(state.queryStart, state.queryEnd))
    }
    var mainMenu by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    var rowMenu by remember { mutableStateOf<String?>(null) }
    val enabled = state.loaded && !state.busy && state.pending == null
    val nativeEnabled = enabled && !state.waitingNative
    val selected = state.visibleSelection
    val enabledLabel = stringResource(R.string.enabled)
    val disabledLabel = stringResource(R.string.disabled)
    val loginLabel = stringResource(R.string.need_login)
    val noGroupLabel = stringResource(R.string.no_group)
    val moveUp = stringResource(R.string.to_top)
    val moveDown = stringResource(R.string.to_bottom)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
        ) {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        query,
                        { value ->
                            query = value
                            actions.query(value.text, value.selection.start, value.selection.end)
                        },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("rss-source-search"),
                        placeholder = { Text(stringResource(R.string.search_rss_source)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    )
                },
                navigationIcon = {
                    IconButton(actions.back, modifier = Modifier.testTag("rss-source-back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(
                            { groupMenu = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("rss-source-groups"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_groups),
                                stringResource(R.string.menu_action_group),
                            )
                        }
                        DropdownMenu(groupMenu, { groupMenu = false }) {
                            ManageMenu(R.string.group_manage) {
                                groupMenu = false
                                actions.effect(RssSourceManagementAction.Groups, null)
                            }
                            listOf(enabledLabel, disabledLabel, loginLabel, noGroupLabel).forEach {
                                label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        groupMenu = false
                                        actions.query(label, label.length, label.length)
                                    },
                                )
                            }
                            state.groups.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group) },
                                    onClick = {
                                        groupMenu = false
                                        val text = "group:$group"
                                        actions.query(text, text.length, text.length)
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(
                            { mainMenu = true },
                            enabled = nativeEnabled,
                            modifier = Modifier.testTag("rss-source-more"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(mainMenu, { mainMenu = false }) {
                            ManageMenu(
                                R.string.add_rss_source,
                                "rss-source-add",
                                R.drawable.ic_add,
                            ) {
                                mainMenu = false
                                actions.effect(RssSourceManagementAction.Add, null)
                            }
                            ManageMenu(
                                R.string.import_local,
                                "rss-source-import-local",
                                R.drawable.ic_import,
                            ) {
                                mainMenu = false
                                actions.effect(RssSourceManagementAction.ImportLocal, null)
                            }
                            ManageMenu(
                                R.string.import_on_line,
                                "rss-source-import-url",
                                R.drawable.ic_import,
                            ) {
                                mainMenu = false
                                actions.dialog(RssSourceManagementDialog.ImportUrl, emptyList())
                            }
                            ManageMenu(
                                R.string.import_by_qr_code,
                                "rss-source-import-qr",
                                R.drawable.ic_import,
                            ) {
                                mainMenu = false
                                actions.effect(RssSourceManagementAction.ImportQr, null)
                            }
                            ManageMenu(
                                R.string.import_default_rule,
                                "rss-source-import-default",
                                R.drawable.ic_import,
                            ) {
                                mainMenu = false
                                actions.defaults()
                            }
                            ManageMenu(R.string.help, icon = R.drawable.ic_help) {
                                mainMenu = false
                                actions.effect(RssSourceManagementAction.Help, null)
                            }
                        }
                    }
                },
            )
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(actions.retry, enabled = !state.busy) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(end = 48.dp),
                    modifier =
                        Modifier.fillMaxSize()
                            .testTag("rss-source-list")
                            .rssSourceSlideSelection(list, gesture, enabled, actions),
                ) {
                    itemsIndexed(state.rows, key = { _, row -> row.id }) { index, row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .testTag("rss-source-row-${row.id}")
                                .rssSourceReorder(row.id, list, gesture, enabled, actions)
                                .semantics {
                                    customActions =
                                        listOf(
                                            CustomAccessibilityAction(moveUp) {
                                                val target = state.rows.getOrNull(index - 1)
                                                if (target != null && actions.beginDrag(row.id)) {
                                                    actions.dragTo(target.id, false)
                                                    actions.finishDrag()
                                                    true
                                                } else false
                                            },
                                            CustomAccessibilityAction(moveDown) {
                                                val target = state.rows.getOrNull(index + 1)
                                                if (target != null && actions.beginDrag(row.id)) {
                                                    actions.dragTo(target.id, true)
                                                    actions.finishDrag()
                                                    true
                                                } else false
                                            },
                                        )
                                }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                row.id in state.selected,
                                { actions.selected(row.id, it) },
                                enabled = enabled,
                                modifier = Modifier.testTag("rss-source-select-${row.id}"),
                            )
                            Text(
                                row.displayName,
                                Modifier.weight(1f).clickable(enabled = enabled) {
                                    actions.selected(row.id, row.id !in state.selected)
                                },
                                maxLines = 1,
                            )
                            Switch(
                                row.enabled,
                                { actions.enabled(listOf(row.id), it) },
                                enabled = enabled,
                                modifier = Modifier.testTag("rss-source-enabled-${row.id}"),
                            )
                            IconButton(
                                { actions.effect(RssSourceManagementAction.Edit, row.id) },
                                enabled = nativeEnabled,
                                modifier = Modifier.testTag("rss-source-edit-${row.id}"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_edit),
                                    stringResource(R.string.edit),
                                )
                            }
                            Box {
                                IconButton(
                                    { rowMenu = row.id },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("rss-source-menu-${row.id}"),
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_more_vert),
                                        stringResource(R.string.more_menu),
                                    )
                                }
                                DropdownMenu(rowMenu == row.id, { rowMenu = null }) {
                                    ManageMenu(R.string.to_top, "rss-source-top-${row.id}") {
                                        rowMenu = null
                                        actions.edge(listOf(row.id), true)
                                    }
                                    ManageMenu(R.string.to_bottom, "rss-source-bottom-${row.id}") {
                                        rowMenu = null
                                        actions.edge(listOf(row.id), false)
                                    }
                                    ManageMenu(
                                        R.string.delete,
                                        "rss-source-delete-${row.id}",
                                        danger = true,
                                    ) {
                                        rowMenu = null
                                        actions.dialog(
                                            RssSourceManagementDialog.Delete,
                                            listOf(row.id),
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                RssSourceManagementFastScroll(
                    list,
                    Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "${selected.size}/${state.rows.size}",
                    Modifier.padding(horizontal = 12.dp).weight(1f).testTag("rss-source-count"),
                )
                TextButton(
                    { if (selected.size == state.rows.size) actions.invert() else actions.all() },
                    enabled = enabled,
                    modifier = Modifier.testTag("rss-source-all"),
                ) {
                    Text(stringResource(R.string.select_all))
                }
                TextButton(
                    actions.invert,
                    enabled = enabled,
                    modifier = Modifier.testTag("rss-source-invert"),
                ) {
                    Text(stringResource(R.string.revert_selection))
                }
                TextButton(
                    { actions.dialog(RssSourceManagementDialog.Delete, selected) },
                    enabled = enabled && selected.isNotEmpty(),
                    modifier = Modifier.testTag("rss-source-delete-selection"),
                ) {
                    Text(stringResource(R.string.delete))
                }
                Box {
                    IconButton(
                        { selectionMenu = true },
                        enabled = enabled,
                        modifier = Modifier.testTag("rss-source-selection-menu"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            stringResource(R.string.more_menu),
                        )
                    }
                    DropdownMenu(selectionMenu, { selectionMenu = false }) {
                        ManageMenu(R.string.enable_selection) {
                            selectionMenu = false
                            actions.enabled(selected, true)
                        }
                        ManageMenu(R.string.disable_selection) {
                            selectionMenu = false
                            actions.enabled(selected, false)
                        }
                        ManageMenu(R.string.add_group) {
                            selectionMenu = false
                            actions.dialog(RssSourceManagementDialog.AddGroup, selected)
                        }
                        ManageMenu(R.string.remove_group) {
                            selectionMenu = false
                            actions.dialog(RssSourceManagementDialog.RemoveGroup, selected)
                        }
                        ManageMenu(R.string.selection_to_top) {
                            selectionMenu = false
                            actions.edge(selected, true)
                        }
                        ManageMenu(R.string.selection_to_bottom) {
                            selectionMenu = false
                            actions.edge(selected, false)
                        }
                        ManageMenu(R.string.export_selection, "rss-source-export") {
                            selectionMenu = false
                            actions.effect(RssSourceManagementAction.Export, null)
                        }
                        ManageMenu(R.string.share_selected_source, "rss-source-share") {
                            selectionMenu = false
                            actions.effect(RssSourceManagementAction.Share, null)
                        }
                        ManageMenu(R.string.check_selected_interval, "rss-source-interval") {
                            selectionMenu = false
                            actions.interval()
                        }
                    }
                }
            }
        }
    }
    state.dialog?.let { dialog -> ManagementDialog(state, dialog, actions) }
}

@Composable
private fun ManageMenu(
    label: Int,
    tag: String = "",
    icon: Int? = null,
    danger: Boolean = false,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                stringResource(label),
                color =
                    if (danger) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon =
            if (icon != null) {
                { Icon(painterResource(icon), null) }
            } else null,
        onClick = action,
        modifier = if (tag.isEmpty()) Modifier else Modifier.testTag(tag),
    )
}

@Composable
private fun ManagementDialog(
    state: RssSourceManagementState,
    dialog: RssSourceManagementDialog,
    actions: RssSourceManagementActions,
) {
    var editor by
        remember(dialog) {
            mutableStateOf(TextFieldValue(state.draft, TextRange(state.draftStart, state.draftEnd)))
        }
    LaunchedEffect(state.draft, state.draftStart, state.draftEnd) {
        if (
            editor.text != state.draft ||
                editor.selection != TextRange(state.draftStart, state.draftEnd)
        )
            editor = TextFieldValue(state.draft, TextRange(state.draftStart, state.draftEnd))
    }
    val output =
        dialog in
            listOf(RssSourceManagementDialog.ExportResult, RssSourceManagementDialog.Passphrase)
    val title =
        when (dialog) {
            RssSourceManagementDialog.Delete -> R.string.draw
            RssSourceManagementDialog.AddGroup -> R.string.add_group
            RssSourceManagementDialog.RemoveGroup -> R.string.remove_group
            RssSourceManagementDialog.ImportUrl -> R.string.import_on_line
            RssSourceManagementDialog.ExportResult -> R.string.export_success
            RssSourceManagementDialog.Passphrase -> R.string.shibboleth
        }
    AlertDialog(
        onDismissRequest = { if (!state.busy) actions.cancelDialog() },
        title = { Text(stringResource(title)) },
        text = {
            Column {
                if (dialog == RssSourceManagementDialog.Delete) {
                    Text(
                        stringResource(R.string.sure_del) +
                            "\n" +
                            state.targets
                                .mapNotNull { id -> state.rows.firstOrNull { it.id == id }?.name }
                                .joinToString("\n")
                    )
                } else {
                    if (dialog == RssSourceManagementDialog.ExportResult)
                        state.feedback?.summary?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    OutlinedTextField(
                        editor,
                        { value ->
                            editor = value
                            actions.draft(value.text, value.selection.start, value.selection.end)
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("rss-source-dialog-field"),
                        label = {
                            Text(
                                if (dialog == RssSourceManagementDialog.ImportUrl) "url"
                                else
                                    stringResource(
                                        if (output) R.string.path else R.string.group_name
                                    )
                            )
                        },
                    )
                    if (dialog == RssSourceManagementDialog.ImportUrl)
                        LazyColumn(Modifier.heightIn(max = 180.dp)) {
                            itemsIndexed(state.history) { _, value ->
                                Row(
                                    verticalAlignment =
                                        androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        { actions.draft(value, value.length, value.length) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(value, maxLines = 1)
                                    }
                                    IconButton(
                                        { actions.forgetImport(value) },
                                        enabled = !state.busy,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_outline_delete),
                                            stringResource(R.string.delete),
                                        )
                                    }
                                }
                            }
                        }
                    if (
                        dialog == RssSourceManagementDialog.AddGroup ||
                            dialog == RssSourceManagementDialog.RemoveGroup
                    )
                        LazyColumn(Modifier.heightIn(max = 180.dp)) {
                            itemsIndexed(state.groups) { _, group ->
                                TextButton(
                                    { actions.draft(group, group.length, group.length) },
                                    enabled = !state.busy,
                                ) {
                                    Text(group)
                                }
                            }
                        }
                    if (
                        dialog == RssSourceManagementDialog.ExportResult &&
                            state.feedback?.canEncode == true
                    )
                        TextButton(
                            actions.passphrase,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("rss-source-passphrase"),
                        ) {
                            Text(stringResource(R.string.shibboleth))
                        }
                }
            }
        },
        confirmButton = {
            TextButton(
                if (output) actions.copy else actions.confirmDialog,
                enabled = !state.busy,
                modifier = Modifier.testTag("rss-source-dialog-confirm"),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(actions.cancelDialog, enabled = !state.busy) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
