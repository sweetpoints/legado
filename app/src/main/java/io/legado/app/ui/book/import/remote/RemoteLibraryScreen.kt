package io.legado.app.ui.book.import.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.model.remote.RemoteLibraryEffect
import io.legado.app.model.remote.RemoteLibraryPrompt
import io.legado.app.model.remote.RemoteLibrarySort
import io.legado.app.utils.ConvertUtils

internal data class RemoteLibraryActions(
    val back: () -> Unit,
    val directoryBack: () -> Unit,
    val query: (String) -> Unit,
    val refresh: () -> Unit,
    val sort: (RemoteLibrarySort) -> Unit,
    val menu: (RemoteLibraryEffect) -> Unit,
    val openDirectory: (String) -> Unit,
    val toggle: (String) -> Unit,
    val read: (String) -> Unit,
    val reimport: (String) -> Unit,
    val all: (Boolean) -> Unit,
    val inverse: () -> Unit,
    val import: () -> Unit,
    val retry: () -> Unit,
    val confirm: () -> Unit,
    val dismiss: () -> Unit,
    val cancelStorage: () -> Unit,
    val archive: (String) -> Unit,
    val retryTask: () -> Unit,
    val discardTask: () -> Unit,
)

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
internal fun RemoteLibraryScreen(state: RemoteLibraryState, actions: RemoteLibraryActions) {
    val list = rememberLazyListState()
    var sortMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val enabled =
        !state.loading &&
            !state.failed &&
            !state.busy &&
            !state.pendingCommit &&
            !state.writeFailed &&
            !state.interrupted
    val menuEnabled =
        !state.loading &&
            !state.busy &&
            !state.pendingCommit &&
            !state.writeFailed &&
            !state.interrupted
    val selected = state.draft?.selected.orEmpty().toHashSet()
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
                        enabled = enabled,
                        singleLine = true,
                        placeholder = {
                            Text(
                                stringResource(R.string.screen) +
                                    " • " +
                                    stringResource(R.string.remote_book)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("remote-library-search"),
                    )
                },
                navigationIcon = {
                    IconButton(actions.back, Modifier.testTag("remote-library-back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        actions.refresh,
                        enabled = enabled,
                        modifier = Modifier.testTag("remote-library-refresh"),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_refresh_black_24dp),
                            stringResource(R.string.refresh),
                        )
                    }
                    Box {
                        IconButton(
                            { sortMenu = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("remote-library-sort"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_sort_24),
                                stringResource(R.string.sort),
                            )
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf(
                                    RemoteLibrarySort.Name to R.string.sort_by_name,
                                    RemoteLibrarySort.Modified to R.string.sort_by_lastUpdateTime,
                                )
                                .forEach { (sort, title) ->
                                    DropdownMenuItem(
                                        { Text(stringResource(title)) },
                                        {
                                            sortMenu = false
                                            actions.sort(sort)
                                        },
                                        trailingIcon = {
                                            RadioButton(state.draft?.sort == sort, null)
                                        },
                                        modifier =
                                            Modifier.testTag("remote-library-sort-${sort.name}"),
                                    )
                                }
                        }
                    }
                    Box {
                        IconButton(
                            { menu = true },
                            enabled = menuEnabled,
                            modifier = Modifier.testTag("remote-library-menu"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            listOf(
                                    RemoteLibraryEffect.Servers to R.string.server_config,
                                    RemoteLibraryEffect.Help to R.string.help,
                                    RemoteLibraryEffect.Log to R.string.log,
                                )
                                .forEach { (effect, title) ->
                                    DropdownMenuItem(
                                        { Text(stringResource(title)) },
                                        {
                                            menu = false
                                            actions.menu(effect)
                                        },
                                        modifier =
                                            Modifier.testTag("remote-library-menu-${effect.name}"),
                                    )
                                }
                        }
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextButton(
                    actions.directoryBack,
                    enabled = enabled && state.draft?.directories?.isNotEmpty() == true,
                    modifier =
                        Modifier.heightIn(min = 48.dp).testTag("remote-library-directory-back"),
                ) {
                    Text(stringResource(R.string.back))
                }
                Text(state.path, Modifier.weight(1f).testTag("remote-library-path"))
            }
            state.error?.let { error ->
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(
                        actions.retry,
                        enabled = !state.busy && !state.interrupted,
                        modifier = Modifier.testTag("remote-library-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    Modifier.fillMaxSize().testTag("remote-library-list"),
                    state = list,
                    contentPadding = PaddingValues(end = 48.dp),
                ) {
                    if (!state.loading && state.visible.isEmpty())
                        item {
                            Text(
                                stringResource(R.string.empty),
                                Modifier.padding(24.dp).testTag("remote-library-empty"),
                            )
                        }
                    items(state.visible, key = { it.id }) { entry ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .combinedClickable(
                                    enabled = enabled,
                                    onClick = {
                                        when {
                                            entry.directory -> actions.openDirectory(entry.id)
                                            entry.onShelf -> actions.read(entry.id)
                                            else -> actions.toggle(entry.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (entry.onShelf && !entry.directory)
                                            actions.reimport(entry.id)
                                    },
                                )
                                .testTag("remote-library-row-${entry.id}")
                                .padding(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            when {
                                entry.directory ->
                                    Icon(
                                        painterResource(R.drawable.ic_folder),
                                        null,
                                        Modifier.size(48.dp),
                                    )
                                entry.onShelf ->
                                    Icon(
                                        painterResource(R.drawable.ic_book_has),
                                        null,
                                        Modifier.size(48.dp),
                                    )
                                else ->
                                    Checkbox(
                                        entry.id in selected,
                                        { actions.toggle(entry.id) },
                                        enabled = enabled,
                                        modifier =
                                            Modifier.testTag("remote-library-selected-${entry.id}"),
                                    )
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                if (!entry.directory) {
                                    Text(
                                        "${entry.type} • ${ConvertUtils.formatFileSize(entry.size)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        AppConst.dateFormat.format(entry.modified),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
                RemoteLibraryFastScroll(
                    list,
                    Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Checkbox(
                    state.checkableCount > 0 && state.selection.size == state.checkableCount,
                    actions.all,
                    enabled = enabled,
                    modifier = Modifier.testTag("remote-library-all"),
                )
                Text(
                    "${state.selection.size} / ${state.checkableCount}",
                    Modifier.weight(1f).testTag("remote-library-count"),
                )
                TextButton(
                    actions.inverse,
                    enabled = enabled,
                    modifier = Modifier.testTag("remote-library-inverse"),
                ) {
                    Text(stringResource(R.string.revert_selection))
                }
                TextButton(
                    actions.import,
                    enabled = enabled && state.selection.isNotEmpty(),
                    modifier = Modifier.testTag("remote-library-import"),
                ) {
                    Text(stringResource(R.string.add_to_bookshelf))
                }
            }
        }
    }
    state.draft?.confirmation?.let { prompt ->
        AlertDialog(
            onDismissRequest =
                if (prompt.kind == RemoteLibraryPrompt.StorageHelp) actions.cancelStorage
                else actions.dismiss,
            title = {
                Text(
                    stringResource(
                        if (prompt.kind == RemoteLibraryPrompt.StorageHelp)
                            R.string.select_book_folder
                        else R.string.draw
                    )
                )
            },
            text = {
                when (prompt.kind) {
                    RemoteLibraryPrompt.StorageHelp ->
                        Text(
                            prompt.help,
                            Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        )
                    RemoteLibraryPrompt.Reimport -> Text("是否重新加入书架？")
                    RemoteLibraryPrompt.DownloadArchive ->
                        Text(stringResource(R.string.archive_not_found))
                    RemoteLibraryPrompt.ImportArchive ->
                        Text(stringResource(R.string.no_book_found_bookshelf))
                    RemoteLibraryPrompt.ChooseArchive ->
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(prompt.names) { name ->
                                Text(
                                    name,
                                    Modifier.fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { actions.archive(name) }
                                        .padding(12.dp)
                                        .testTag("remote-library-archive-$name"),
                                )
                            }
                        }
                }
            },
            confirmButton = {
                if (prompt.kind != RemoteLibraryPrompt.ChooseArchive)
                    TextButton(actions.confirm, Modifier.testTag("remote-library-confirm")) {
                        Text(stringResource(R.string.ok))
                    }
            },
            dismissButton = {
                TextButton(
                    if (prompt.kind == RemoteLibraryPrompt.StorageHelp) actions.cancelStorage
                    else actions.dismiss,
                    Modifier.testTag("remote-library-prompt-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.busy || state.pendingCommit)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.add_to_bookshelf)) },
            text = {
                Column {
                    if (state.busy) CircularProgressIndicator()
                    if (state.total > 0) Text("${state.progress} / ${state.total}")
                    state.error?.let { Text(it) }
                }
            },
            confirmButton = {
                if (!state.busy && state.pendingCommit)
                    TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
            },
        )
    if (state.interrupted && !state.busy && !state.pendingCommit)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.retry)) },
            text = { Text(state.error ?: stringResource(R.string.add_to_bookshelf)) },
            confirmButton = {
                TextButton(actions.retryTask, Modifier.testTag("remote-library-retry-task")) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(actions.discardTask, Modifier.testTag("remote-library-discard-task")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}
