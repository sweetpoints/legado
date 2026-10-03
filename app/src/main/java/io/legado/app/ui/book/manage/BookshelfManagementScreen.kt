package io.legado.app.ui.book.manage

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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.bookshelf.*

internal val shelfSelectionActions =
    listOf(
        ShelfManagementAction.Delete,
        ShelfManagementAction.EnableUpdate,
        ShelfManagementAction.DisableUpdate,
        ShelfManagementAction.GroupAdd,
        ShelfManagementAction.GroupRemove,
        ShelfManagementAction.ChangeSource,
        ShelfManagementAction.ClearCache,
        ShelfManagementAction.PersistCovers,
        ShelfManagementAction.RestoreNetworkCovers,
        ShelfManagementAction.RestoreSourceCovers,
        ShelfManagementAction.UpdateToc,
        ShelfManagementAction.CreateTasks,
    )

internal fun ShelfManagementAction.titleResource() =
    when (this) {
        ShelfManagementAction.Delete -> R.string.delete
        ShelfManagementAction.EnableUpdate -> R.string.allow_update
        ShelfManagementAction.DisableUpdate -> R.string.disable_update
        ShelfManagementAction.GroupAdd -> R.string.add_to_group
        ShelfManagementAction.GroupRemove -> R.string.remove_group
        ShelfManagementAction.ChangeSource -> R.string.change_source_batch
        ShelfManagementAction.ClearCache -> R.string.clear_cache
        ShelfManagementAction.PersistCovers -> R.string.persist_network_covers
        ShelfManagementAction.RestoreNetworkCovers -> R.string.restore_network_covers
        ShelfManagementAction.RestoreSourceCovers -> R.string.restore_source_covers
        ShelfManagementAction.UpdateToc -> R.string.update_book_info_toc
        ShelfManagementAction.CreateTasks -> R.string.create_book_update_task
        ShelfManagementAction.ExportSources -> R.string.export_all_use_book_source
        ShelfManagementAction.GroupReplace -> R.string.move_to_group
        ShelfManagementAction.ToggleTitle -> R.string.open_book_info_by_click_title
        ShelfManagementAction.Reorder -> R.string.bookshelf_management
    }

internal data class BookshelfManagementActions(
    val back: () -> Unit,
    val query: (String) -> Unit,
    val group: (Long) -> Unit,
    val toggle: (String) -> Unit,
    val all: (Boolean) -> Unit,
    val inverse: () -> Unit,
    val interval: () -> Unit,
    val action: (ShelfManagementAction) -> Unit,
    val rowDelete: (String) -> Unit,
    val rowGroup: (String, Long) -> Unit,
    val open: (String) -> Unit,
    val groups: () -> Unit,
    val openTitle: (Boolean) -> Unit,
    val retry: () -> Unit,
    val retryOperation: () -> Unit,
    val dismiss: () -> Unit,
    val confirm: () -> Unit,
    val original: (Boolean) -> Unit,
    val cron: (String, Int, Int) -> Unit,
    val cancelOperation: () -> Unit,
    val beginSelection: () -> Boolean,
    val selectionRange: (String, String) -> Unit,
    val finishSelection: () -> Unit,
    val beginDrag: (String) -> Boolean,
    val dragTo: (String) -> Unit,
    val finishDrag: () -> Unit,
    val cancelGesture: () -> Unit,
    val closeExport: () -> Unit,
    val copyExport: (String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfManagementScreen(
    state: BookshelfManagementState,
    actions: BookshelfManagementActions,
) {
    val list = rememberLazyListState()
    val focus = LocalFocusManager.current
    val gesture = rememberShelfManagementGesture(list, actions)
    var groupMenu by remember { mutableStateOf(false) }
    var mainMenu by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }
    val enabled =
        !state.loading &&
            !state.failed &&
            !state.busy &&
            !state.pendingCommit &&
            !state.writeFailed &&
            !state.interrupted
    val snapshot = state.snapshot
    val selected = state.visibleSelection.toHashSet()
    val count = snapshot?.books.orEmpty().size
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
        ) {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        state.draft?.query.orEmpty(),
                        actions.query,
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("shelf-manage-search"),
                        placeholder = {
                            Text(
                                stringResource(R.string.screen) +
                                    " • " +
                                    (snapshot?.groupName ?: stringResource(R.string.no_group))
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    )
                },
                navigationIcon = {
                    IconButton(actions.back, Modifier.testTag("shelf-manage-back")) {
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
                            modifier = Modifier.testTag("shelf-manage-groups"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_groups),
                                stringResource(R.string.group),
                            )
                        }
                        DropdownMenu(groupMenu, { groupMenu = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.group_manage)) },
                                {
                                    groupMenu = false
                                    actions.groups()
                                },
                                modifier = Modifier.testTag("shelf-manage-group-manage"),
                            )
                            snapshot?.groups.orEmpty().forEach { group ->
                                DropdownMenuItem(
                                    { Text(group.name) },
                                    {
                                        groupMenu = false
                                        actions.group(group.id)
                                    },
                                    modifier = Modifier.testTag("shelf-manage-group-${group.id}"),
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(
                            { mainMenu = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("shelf-manage-more"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(mainMenu, { mainMenu = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.export_all_use_book_source)) },
                                {
                                    mainMenu = false
                                    actions.action(ShelfManagementAction.ExportSources)
                                },
                                modifier = Modifier.testTag("shelf-manage-export"),
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.open_book_info_by_click_title)) },
                                {
                                    mainMenu = false
                                    actions.openTitle(snapshot?.openTitle != true)
                                },
                                trailingIcon = { Checkbox(snapshot?.openTitle == true, null) },
                                modifier = Modifier.testTag("shelf-manage-open-title"),
                            )
                        }
                    }
                },
            )
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(actions.retry, enabled = !state.busy) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(end = 48.dp),
                    modifier =
                        Modifier.fillMaxSize()
                            .testTag("shelf-manage-list")
                            .shelfManagementSlideSelection(list, gesture, enabled, actions),
                ) {
                    itemsIndexed(snapshot?.books.orEmpty(), key = { _, book -> book.id }) { _, book
                        ->
                        Column(
                            Modifier.fillMaxWidth()
                                .shelfManagementReorder(
                                    book.id,
                                    list,
                                    gesture,
                                    enabled && snapshot?.sort == 3,
                                    actions,
                                )
                                .clickable(enabled = enabled) { actions.toggle(book.id) }
                                .testTag("shelf-manage-row-${book.id}")
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Row(Modifier.fillMaxWidth()) {
                                Checkbox(
                                    book.id in selected,
                                    { actions.toggle(book.id) },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("shelf-manage-selected-${book.id}"),
                                )
                                Column(Modifier.weight(1f).padding(top = 8.dp, end = 8.dp)) {
                                    Text(
                                        book.name,
                                        Modifier.fillMaxWidth()
                                            .then(
                                                if (snapshot?.openTitle == true)
                                                    Modifier.clickable(enabled = enabled) {
                                                        actions.open(book.id)
                                                    }
                                                else Modifier
                                            )
                                            .testTag("shelf-manage-title-${book.id}"),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (book.author.isNotEmpty())
                                        Text(
                                            book.author,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    Text(
                                        if (book.local) stringResource(R.string.local_book)
                                        else book.originName,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(start = 48.dp)) {
                                Text(
                                    book.groupNames.ifEmpty { stringResource(R.string.no_group) },
                                    Modifier.weight(1f).padding(top = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    { actions.rowGroup(book.id, book.group) },
                                    enabled = enabled,
                                    modifier =
                                        Modifier.heightIn(min = 48.dp)
                                            .testTag("shelf-manage-row-group-${book.id}"),
                                ) {
                                    Text(stringResource(R.string.group))
                                }
                                TextButton(
                                    { actions.rowDelete(book.id) },
                                    enabled = enabled,
                                    modifier =
                                        Modifier.heightIn(min = 48.dp)
                                            .testTag("shelf-manage-row-delete-${book.id}"),
                                ) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                BookshelfManagementFastScroll(
                    list,
                    Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Checkbox(
                    count > 0 && selected.size == count,
                    { actions.all(it) },
                    enabled = enabled,
                    modifier = Modifier.testTag("shelf-manage-all"),
                )
                Text("${selected.size} / $count", Modifier.weight(1f).testTag("shelf-manage-count"))
                TextButton(
                    { actions.action(ShelfManagementAction.GroupReplace) },
                    enabled = enabled && selected.isNotEmpty(),
                    modifier = Modifier.testTag("shelf-manage-move"),
                ) {
                    Text(stringResource(R.string.move_to_group))
                }
                Box {
                    IconButton(
                        { selectionMenu = true },
                        enabled = enabled,
                        modifier = Modifier.testTag("shelf-manage-selection-menu"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_more_vert),
                            stringResource(R.string.more_menu),
                        )
                    }
                    DropdownMenu(selectionMenu, { selectionMenu = false }) {
                        DropdownMenuItem(
                            { Text(stringResource(R.string.revert_selection)) },
                            {
                                selectionMenu = false
                                actions.inverse()
                            },
                            modifier = Modifier.testTag("shelf-manage-inverse"),
                        )
                        shelfSelectionActions.forEach { action ->
                            if (action == ShelfManagementAction.UpdateToc)
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.check_selected_interval)) },
                                    {
                                        selectionMenu = false
                                        actions.interval()
                                    },
                                    modifier = Modifier.testTag("shelf-manage-interval"),
                                )
                            DropdownMenuItem(
                                { Text(stringResource(action.titleResource())) },
                                {
                                    selectionMenu = false
                                    actions.action(action)
                                },
                                enabled = selected.isNotEmpty(),
                                modifier = Modifier.testTag("shelf-manage-action-${action.name}"),
                            )
                        }
                    }
                }
            }
        }
    }
    state.draft?.confirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(stringResource(confirmation.action.titleResource())) },
            text = {
                Column {
                    when (confirmation.action) {
                        ShelfManagementAction.Delete -> {
                            Text(stringResource(R.string.sure_del))
                            if (confirmation.showOriginal)
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        actions.original(!confirmation.deleteOriginal)
                                    }
                                ) {
                                    Checkbox(
                                        confirmation.deleteOriginal,
                                        actions.original,
                                        modifier = Modifier.testTag("shelf-manage-delete-original"),
                                    )
                                    Text(
                                        stringResource(R.string.delete_book_file),
                                        Modifier.padding(top = 12.dp),
                                    )
                                }
                        }
                        ShelfManagementAction.RestoreNetworkCovers ->
                            Text(stringResource(R.string.restore_network_covers_confirm))
                        ShelfManagementAction.RestoreSourceCovers ->
                            Text(stringResource(R.string.restore_source_covers_confirm))
                        ShelfManagementAction.CreateTasks ->
                            OutlinedTextField(
                                TextFieldValue(
                                    confirmation.cron,
                                    TextRange(
                                        confirmation.selectionStart,
                                        confirmation.selectionEnd,
                                    ),
                                ),
                                { actions.cron(it.text, it.selection.start, it.selection.end) },
                                modifier = Modifier.testTag("shelf-manage-cron"),
                                label = { Text(stringResource(R.string.auto_task_cron_hint)) },
                                isError = state.invalidCron,
                                supportingText = {
                                    if (state.invalidCron)
                                        Text(stringResource(R.string.auto_task_cron_invalid))
                                },
                            )
                        else -> Unit
                    }
                }
            },
            confirmButton = {
                TextButton(actions.confirm, Modifier.testTag("shelf-manage-confirm")) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.dismiss) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    if (state.busy || state.pendingCommit)
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    stringResource(
                        state.draft?.operation?.action?.titleResource()
                            ?: R.string.bookshelf_management
                    )
                )
            },
            text = {
                Column {
                    if (state.busy) CircularProgressIndicator()
                    state.progress?.let { Text(it) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (state.pendingCommit && !state.busy)
                    TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
            },
            dismissButton = {
                if (
                    state.busy &&
                        state.draft?.operation?.action in
                            listOf(
                                ShelfManagementAction.ChangeSource,
                                ShelfManagementAction.PersistCovers,
                            )
                )
                    TextButton(
                        actions.cancelOperation,
                        Modifier.testTag("shelf-manage-cancel-operation"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
            },
        )
    if (state.interrupted && !state.pendingCommit && !state.busy)
        AlertDialog(
            onDismissRequest = actions.back,
            title = { Text(stringResource(R.string.retry)) },
            text = { Text(state.error ?: stringResource(R.string.sure_del)) },
            confirmButton = {
                TextButton(
                    actions.retryOperation,
                    Modifier.testTag("shelf-manage-retry-operation"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = { TextButton(actions.back) { Text(stringResource(R.string.cancel)) } },
        )
    state.draft?.exportResult?.let { uri ->
        AlertDialog(
            onDismissRequest = actions.closeExport,
            title = { Text(stringResource(R.string.export_success)) },
            text = {
                Column {
                    state.draft?.exportSummary?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                    OutlinedTextField(
                        uri,
                        {},
                        readOnly = true,
                        modifier = Modifier.testTag("shelf-manage-export-result"),
                        label = { Text(stringResource(R.string.path)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    {
                        actions.copyExport(uri)
                        actions.closeExport()
                    },
                    Modifier.testTag("shelf-manage-export-copy"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}
