package io.legado.app.ui.book.changesource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import kotlinx.coroutines.launch

internal enum class BookSourceMenu {
    Manage,
    Refresh,
    Author,
    WordCount,
    ResponseTime,
    WordCountFilter,
    Info,
    Toc,
    Group,
    Close,
}

internal val bookSourceMenuOrder = BookSourceMenu.entries.toList()

internal enum class BookSourceRowAction {
    Top,
    Bottom,
    Edit,
    Disable,
    Delete,
}

internal val bookSourceRowActions = BookSourceRowAction.entries.toList()

internal data class BookSourceScreenActions(
    val close: () -> Unit,
    val startStop: () -> Unit,
    val searchOpen: () -> Unit,
    val query: (String) -> Unit,
    val retry: () -> Unit,
    val menu: (BookSourceMenu) -> Unit,
    val group: (String) -> Unit,
    val choose: (String) -> Unit,
    val rowAction: (String, BookSourceRowAction) -> Unit,
    val score: (String, Int) -> Unit,
    val dismissEmpty: () -> Unit,
    val searchAll: () -> Unit,
    val confirmMismatch: () -> Unit,
    val dismissMismatch: () -> Unit,
    val cancelChange: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun BookSourceScreen(
    state: BookSourceState,
    actions: BookSourceScreenActions,
    modifier: Modifier = Modifier,
    groups: List<String> = emptyList(),
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var groupMenu by rememberSaveable { mutableStateOf(false) }
    var selectedRow by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteRow by rememberSaveable { mutableStateOf<String?>(null) }
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val busy =
        state.loading ||
            state.busy ||
            state.changing ||
            state.persistError ||
            state.pendingReceipt != null
    LaunchedEffect(
        state.rows.firstOrNull()?.id,
        state.rows.any { it.id == state.request?.currentBookUrl },
    ) {
        if (state.rows.isNotEmpty()) {
            val current = state.rows.indexOfFirst { it.id == state.request?.currentBookUrl }
            if (!initialScrollDone && current >= 0) {
                list.scrollToItem(current, -60)
                initialScrollDone = true
            } else list.scrollToItem(0)
        }
    }
    Surface(modifier) {
        Column(Modifier.fillMaxSize().imePadding().testTag("book-source-screen")) {
            TopAppBar(
                title = {
                    if (!state.searchOpen)
                        Column {
                            Text(
                                state.request?.name.orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                state.request?.author.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                },
                navigationIcon = {
                    IconButton(actions.close, Modifier.testTag("book-source-close")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        actions.searchOpen,
                        Modifier.testTag("book-source-search-open"),
                        enabled = !state.loading,
                    ) {
                        Icon(painterResource(R.drawable.ic_screen), stringResource(R.string.screen))
                    }
                    IconButton(
                        actions.startStop,
                        Modifier.testTag("book-source-start-stop"),
                        enabled = !busy,
                    ) {
                        Icon(
                            painterResource(
                                if (state.searching) R.drawable.ic_stop_black_24dp
                                else R.drawable.ic_refresh_black_24dp
                            ),
                            stringResource(
                                if (state.searching) R.string.stop else R.string.refresh
                            ),
                        )
                    }
                    Box {
                        IconButton({ menu = true }, Modifier.testTag("book-source-menu")) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }) {
                            bookSourceMenuOrder.forEach { action ->
                                val label =
                                    when (action) {
                                        BookSourceMenu.Manage -> R.string.book_source_manage
                                        BookSourceMenu.Refresh -> R.string.refresh_list
                                        BookSourceMenu.Author -> R.string.checkAuthor
                                        BookSourceMenu.WordCount -> R.string.load_word_count
                                        BookSourceMenu.ResponseTime ->
                                            R.string.change_source_sort_respond_time
                                        BookSourceMenu.WordCountFilter ->
                                            R.string.change_source_word_count_filter
                                        BookSourceMenu.Info -> R.string.load_info
                                        BookSourceMenu.Toc -> R.string.load_toc
                                        BookSourceMenu.Group -> R.string.group
                                        BookSourceMenu.Close -> R.string.close
                                    }
                                val checked =
                                    when (action) {
                                        BookSourceMenu.Author -> state.request?.checkAuthor
                                        BookSourceMenu.WordCount -> state.request?.loadWordCount
                                        BookSourceMenu.ResponseTime ->
                                            state.request?.sortResponseTime
                                        BookSourceMenu.Info -> state.request?.loadInfo
                                        BookSourceMenu.Toc -> state.request?.loadToc
                                        BookSourceMenu.WordCountFilter ->
                                            state.request?.filterMode?.let { it != 0 }
                                        else -> null
                                    }
                                val group =
                                    state.request
                                        ?.group
                                        ?.takeIf {
                                            it.isNotEmpty() && action == BookSourceMenu.Group
                                        }
                                        ?.let { "($it)" }
                                        .orEmpty()
                                DropdownMenuItem(
                                    {
                                        Text(
                                            (if (checked == true) "✓ " else "") +
                                                stringResource(label) +
                                                group
                                        )
                                    },
                                    {
                                        menu = false
                                        if (action == BookSourceMenu.Group) groupMenu = true
                                        else actions.menu(action)
                                    },
                                    enabled =
                                        !busy ||
                                            action == BookSourceMenu.Close ||
                                            action == BookSourceMenu.Manage,
                                    modifier = Modifier.testTag("book-source-menu-${action.name}"),
                                )
                            }
                        }
                    }
                },
            )
            if (state.searchOpen)
                OutlinedTextField(
                    state.request?.query.orEmpty(),
                    actions.query,
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("book-source-query"),
                    singleLine = true,
                    label = { Text(stringResource(R.string.screen)) },
                )
            if (state.loading || state.searching || state.changing)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("book-source-progress"))
            state.error?.let { message ->
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        Modifier.weight(1f).testTag("book-source-error"),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        actions.retry,
                        Modifier.testTag("book-source-retry"),
                        enabled = !state.loading && !state.changing,
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("book-source-results"),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    val current = row.id == state.request?.currentBookUrl
                    Surface(
                        color =
                            if (current)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
                            else MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .combinedClickable(
                                    enabled = !busy,
                                    onClick = { if (!current) actions.choose(row.id) },
                                    onLongClick = { selectedRow = row.id },
                                )
                                .padding(12.dp)
                                .testTag("book-source-row-${row.id}")
                                .semantics { selected = current }
                        ) {
                            Text(
                                (if (current) "✓ " else "") + row.originName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(row.author)
                            Text(row.latest)
                            if (state.request?.loadWordCount == true) {
                                row.wordCountText
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let {
                                        Text(it, Modifier.testTag("book-source-count-${row.id}"))
                                    }
                                if (row.responseTime >= 0)
                                    Text(
                                        stringResource(R.string.respondTime, row.responseTime),
                                        Modifier.testTag("book-source-time-${row.id}"),
                                    )
                            }
                            Row {
                                if (row.score >= 0)
                                    TextButton(
                                        { actions.score(row.id, 1) },
                                        Modifier.heightIn(min = 48.dp)
                                            .testTag("book-source-good-${row.id}"),
                                        enabled = !busy,
                                    ) {
                                        Text(if (row.score > 0) "👍 ✓" else "👍")
                                    }
                                if (row.score <= 0)
                                    TextButton(
                                        { actions.score(row.id, -1) },
                                        Modifier.heightIn(min = 48.dp)
                                            .testTag("book-source-bad-${row.id}"),
                                        enabled = !busy,
                                    ) {
                                        Text(if (row.score < 0) "👎 ✓" else "👎")
                                    }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    {
                        state.rows
                            .indexOfFirst { it.id == state.request?.currentBookUrl }
                            .takeIf { it >= 0 }
                            ?.let { scope.launch { list.scrollToItem(it, -60) } }
                    },
                    Modifier.weight(1f).heightIn(min = 48.dp).testTag("book-source-current"),
                ) {
                    Text(
                        if (state.completed == 0 && state.sourceName.isEmpty()) state.originName
                        else
                            stringResource(
                                R.string.change_source_progress,
                                state.rows.size,
                                state.completed,
                                state.total,
                                state.sourceName,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    { scope.launch { if (state.rows.isNotEmpty()) list.scrollToItem(0) } },
                    Modifier.testTag("book-source-top"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_drop_up),
                        stringResource(R.string.go_to_top),
                    )
                }
                IconButton(
                    {
                        scope.launch {
                            if (state.rows.isNotEmpty()) list.scrollToItem(state.rows.lastIndex)
                        }
                    },
                    Modifier.testTag("book-source-bottom"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_drop_down),
                        stringResource(R.string.go_to_bottom),
                    )
                }
            }
        }
        if (groupMenu)
            AlertDialog(
                onDismissRequest = { groupMenu = false },
                title = { Text(stringResource(R.string.group)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 360.dp).testTag("book-source-groups")) {
                        items((listOf("") + groups).distinct(), key = { it }) { group ->
                            TextButton(
                                {
                                    groupMenu = false
                                    actions.group(group)
                                },
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("book-source-group-$group"),
                            ) {
                                Text(
                                    (if (group == state.request?.group) "✓ " else "") +
                                        group.ifEmpty { stringResource(R.string.all_source) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton({ groupMenu = false }) { Text(stringResource(R.string.close)) }
                },
            )
        val row = state.rows.find { it.id == selectedRow }
        if (row != null)
            AlertDialog(
                onDismissRequest = { selectedRow = null },
                title = { Text(row.originName) },
                text = {
                    Column {
                        bookSourceRowActions.forEach { action ->
                            val label =
                                when (action) {
                                    BookSourceRowAction.Top -> R.string.to_top
                                    BookSourceRowAction.Bottom -> R.string.to_bottom
                                    BookSourceRowAction.Edit -> R.string.edit_source
                                    BookSourceRowAction.Disable -> R.string.disable_source
                                    BookSourceRowAction.Delete -> R.string.delete_source
                                }
                            TextButton(
                                {
                                    selectedRow = null
                                    if (action == BookSourceRowAction.Delete) deleteRow = row.id
                                    else actions.rowAction(row.id, action)
                                },
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("book-source-row-action-${action.name}"),
                                enabled = !busy,
                            ) {
                                Text(
                                    stringResource(label),
                                    color =
                                        if (action == BookSourceRowAction.Delete)
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton({ selectedRow = null }) { Text(stringResource(R.string.close)) }
                },
            )
        state.rows
            .find { it.id == deleteRow }
            ?.let { target ->
                AlertDialog(
                    onDismissRequest = { deleteRow = null },
                    title = { Text(stringResource(R.string.draw)) },
                    text = { Text(stringResource(R.string.sure_del) + "\n" + target.originName) },
                    confirmButton = {
                        TextButton(
                            {
                                deleteRow = null
                                actions.rowAction(target.id, BookSourceRowAction.Delete)
                            },
                            Modifier.testTag("book-source-delete-confirm"),
                            enabled = !busy,
                        ) {
                            Text(stringResource(R.string.yes))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            { deleteRow = null },
                            Modifier.testTag("book-source-delete-cancel"),
                        ) {
                            Text(stringResource(R.string.no))
                        }
                    },
                )
            }
        if (state.emptyGroup)
            AlertDialog(
                actions.dismissEmpty,
                title = { Text("搜索结果为空") },
                text = { Text("${state.request?.group.orEmpty()}分组搜索结果为空,是否切换到全部分组") },
                confirmButton = {
                    TextButton(actions.searchAll, Modifier.testTag("book-source-empty-confirm")) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(actions.dismissEmpty, Modifier.testTag("book-source-empty-cancel")) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        if (state.mismatchId != null)
            AlertDialog(
                actions.dismissMismatch,
                title = { Text(stringResource(R.string.book_type_different)) },
                text = { Text(stringResource(R.string.soure_change_source)) },
                confirmButton = {
                    TextButton(
                        actions.confirmMismatch,
                        Modifier.testTag("book-source-mismatch-confirm"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        actions.dismissMismatch,
                        Modifier.testTag("book-source-mismatch-cancel"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        if (state.changing)
            AlertDialog(
                onDismissRequest = { if (state.changeCancelable) actions.cancelChange() },
                properties =
                    DialogProperties(
                        dismissOnBackPress = state.changeCancelable,
                        dismissOnClickOutside = state.changeCancelable,
                    ),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.load_toc), Modifier.padding(12.dp))
                    }
                },
                confirmButton = {
                    if (state.changeCancelable)
                        TextButton(
                            actions.cancelChange,
                            Modifier.testTag("book-source-change-cancel"),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                },
            )
    }
}
