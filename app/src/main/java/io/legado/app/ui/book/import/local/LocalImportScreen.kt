package io.legado.app.ui.book.import.local

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.utils.ConvertUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun LocalImportRoute(
    model: LocalImportViewModel,
    back: () -> Unit,
    native: (LocalImportNative) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(native)
    LaunchedEffect(owner, model) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.state.collect { current ->
                current.pending?.let { pending ->
                    try {
                        val receipt = model.claimNative(pending.nonce)
                        currentCoroutineContext().ensureActive()
                        if (receipt != null) deliver(receipt)
                    } catch (cancelled: CancellationException) {
                        model.nativeInterrupted()
                        throw cancelled
                    } catch (error: Exception) {
                        model.nativeInterrupted()
                    }
                }
            }
        }
    }
    LocalImportScreen(
        state,
        back,
        model::click,
        model::search,
        model::sort,
        model::requestFolder,
        model::scan,
        model::cancelScan,
        model::all,
        model::invert,
        model::deleteSelection,
        model::importSelection,
        { model.showStorage(true) },
        { model.editScript(true) },
    )
    LocalImportDialogs(state, model)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalImportScreen(
    state: LocalImportUiState,
    back: () -> Unit,
    click: (String) -> Unit,
    search: (String) -> Unit,
    sort: (Int) -> Unit,
    folder: () -> Unit,
    scan: () -> Unit,
    cancelScan: () -> Unit,
    selectAll: (Boolean) -> Unit,
    invert: () -> Unit,
    delete: () -> Unit,
    onImport: () -> Unit,
    storage: () -> Unit,
    script: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.local_book)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.go_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = folder) {
                        Icon(
                            painterResource(R.drawable.ic_folder_open),
                            stringResource(R.string.select_folder),
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            listOf(
                                    R.string.sort_by_name,
                                    R.string.sort_by_size,
                                    R.string.sort_by_time,
                                )
                                .forEachIndexed { index, label ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                (if (state.sort == index) "✓ " else "") +
                                                    stringResource(label)
                                            )
                                        },
                                        onClick = {
                                            menu = false
                                            sort(index)
                                        },
                                    )
                                }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.scan_folder)) },
                                onClick = {
                                    menu = false
                                    scan()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_file_name)) },
                                onClick = {
                                    menu = false
                                    script()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.local_book_save_path)) },
                                onClick = {
                                    menu = false
                                    storage()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val checkable = state.rows.filter { !it.directory && !it.onShelf }
            FlowRow(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked =
                            checkable.isNotEmpty() && checkable.all { it.id in state.selected },
                        onCheckedChange = selectAll,
                        modifier = Modifier.testTag("local-import-all"),
                    )
                    Text("${state.selected.size}/${checkable.size}")
                }
                TextButton(onClick = invert) { Text(stringResource(R.string.revert_selection)) }
                TextButton(
                    onClick = delete,
                    enabled = state.selected.isNotEmpty() && !state.importing,
                ) {
                    Text(stringResource(R.string.delete))
                }
                TextButton(
                    onClick = onImport,
                    enabled = state.selected.isNotEmpty() && !state.importing,
                    modifier = Modifier.testTag("local-import-add"),
                ) {
                    Text(stringResource(R.string.add_to_bookshelf))
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            OutlinedTextField(
                state.query,
                search,
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("local-import-search"),
                singleLine = true,
                label = {
                    Text(
                        stringResource(R.string.screen) +
                            " • " +
                            stringResource(R.string.local_book)
                    )
                },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.path, Modifier.weight(1f))
                TextButton(onClick = back, enabled = state.canGoBack) {
                    Text(stringResource(R.string.go_back))
                }
            }
            if (state.loading || state.importing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.loading && state.recursive)
                    TextButton(onClick = cancelScan) { Text(stringResource(R.string.cancel)) }
            }
            if (state.rows.isEmpty() && !state.loading)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_msg_import_book), Modifier.padding(16.dp))
                }
            else
                LazyColumn(Modifier.weight(1f).testTag("local-import-list")) {
                    items(state.rows, key = { it.id }) { row ->
                        Row(
                            Modifier.fillMaxWidth().clickable { click(row.id) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            when {
                                row.directory ->
                                    Icon(
                                        painterResource(R.drawable.ic_folder),
                                        null,
                                        Modifier.padding(8.dp),
                                    )
                                row.onShelf ->
                                    Icon(
                                        painterResource(R.drawable.ic_book_has),
                                        stringResource(R.string.start_read),
                                        Modifier.padding(8.dp),
                                    )
                                else ->
                                    Checkbox(
                                        row.id in state.selected,
                                        { click(row.id) },
                                        Modifier.testTag("local-import-select-${row.id}"),
                                    )
                            }
                            Column(Modifier.weight(1f).padding(8.dp)) {
                                Text(row.name)
                                if (!row.directory)
                                    Text(
                                        "${row.name.substringAfterLast('.')}  ${ConvertUtils.formatFileSize(row.size)}  ${AppConst.dateFormat.format(row.modified)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                            }
                        }
                        HorizontalDivider()
                    }
                }
        }
    }
}

@Composable
private fun LocalImportDialogs(state: LocalImportUiState, model: LocalImportViewModel) {
    if (state.storagePrompt)
        AlertDialog(
            onDismissRequest = model::skipStoragePrompt,
            title = { Text(stringResource(R.string.select_book_folder)) },
            text = { Text(state.storageHelp) },
            confirmButton = {
                TextButton(onClick = model::storagePromptAccepted) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = model::skipStoragePrompt) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    if (state.showStorage)
        AlertDialog(
            onDismissRequest = { model.showStorage(false) },
            title = { Text(stringResource(R.string.local_book_save_path)) },
            text = { Text(state.storage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = model::requestStorage) {
                    Text(stringResource(R.string.select_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = model::privateStorage) {
                    Text(stringResource(R.string.shared_local_books_private))
                }
            },
        )
    if (state.editScript) {
        var draft by remember(state.script) { mutableStateOf(state.script) }
        AlertDialog(
            onDismissRequest = { model.editScript(false) },
            title = { Text(stringResource(R.string.import_file_name)) },
            text = {
                Column {
                    Text("使用js处理文件名变量src，将书名作者分别赋值到变量name author")
                    OutlinedTextField(
                        draft,
                        { draft = it },
                        label = { Text("js") },
                        modifier = Modifier.heightIn(max = 360.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { model.saveScript(draft) }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { model.editScript(false) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.group?.let { group ->
        var checked by remember(group) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_directory_group_title)) },
            text = {
                Column {
                    if (!group.available) Text(stringResource(R.string.book_group_limit))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, { checked = it }, enabled = group.available)
                        Text(stringResource(R.string.import_directory_group))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { model.confirmGroup(checked) }, enabled = group.available) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { model.confirmGroup(false) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.archive?.let { archive ->
        AlertDialog(
            onDismissRequest = model::dismissArchive,
            title = {
                Text(
                    stringResource(
                        if (archive.selected == null) R.string.start_read else R.string.draw
                    )
                )
            },
            text = {
                if (archive.selected != null) Text(stringResource(R.string.no_book_found_bookshelf))
                else
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(archive.names) { name ->
                            TextButton(onClick = { model.chooseArchive(name) }) { Text(name) }
                        }
                    }
            },
            confirmButton = {
                if (archive.selected != null)
                    TextButton(onClick = model::importArchive) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = model::dismissArchive) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.recovery)
        AlertDialog(
            onDismissRequest = model::dismissRecovery,
            title = { Text(stringResource(R.string.local_import_recovery_title)) },
            text = { Text(stringResource(R.string.local_import_recovery_message)) },
            confirmButton = {
                TextButton(onClick = model::retryRecovery) { Text(stringResource(R.string.retry)) }
            },
            dismissButton = {
                TextButton(onClick = model::dismissRecovery) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    (state.error ?: state.message)?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissMessage,
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = model::dismissMessage) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}
