package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookSourceCheckState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class SourceManagerActions(
    val query: (String) -> Unit,
    val toggle: (String) -> Unit,
    val selectAll: () -> Unit,
    val invert: () -> Unit,
    val action: (String, String) -> Unit,
    val mutation: (SourceMutation, String?) -> Unit,
    val sort: (BookSourceSort) -> Unit,
    val status: (String) -> Unit,
    val draft: (String) -> Unit,
    val forgetImport: (String) -> Unit,
    val confirm: () -> Unit,
    val dismiss: () -> Unit,
    val beginDrag: (String) -> Unit,
    val previewDrag: (Int) -> Unit,
    val finishDrag: (Boolean) -> Unit,
    val slide: (Set<String>, Set<String>) -> Unit,
)

@Composable
internal fun BookSourceManagerScreen(
    state: SourceManagerState,
    actions: SourceManagerActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val selectedCount = state.rows.count { it.url in state.selected }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ actions.action("back", "") }) { Text(stringResource(R.string.back)) }
                Text(
                    stringResource(R.string.book_source),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                Box {
                    TextButton({ menuOpen = true }) { Text(stringResource(R.string.menu)) }
                    DropdownMenu(menuOpen, { menuOpen = false }, Modifier.heightIn(max = 500.dp)) {
                        managerMenu(state).forEach { (label, action) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    menuOpen = false
                                    actions.action(action, "")
                                },
                                enabled = !state.busy,
                            )
                        }
                        listOf(
                                BookSourceSort.Default,
                                BookSourceSort.Weight,
                                BookSourceSort.Name,
                                BookSourceSort.Url,
                                BookSourceSort.Update,
                                BookSourceSort.Respond,
                                BookSourceSort.Enable,
                            )
                            .forEach { sort ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (state.sort == sort) "✓ " else "") + sortName(sort)
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        actions.sort(sort)
                                    },
                                    enabled = !state.busy,
                                )
                            }
                        state.groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group) },
                                onClick = {
                                    menuOpen = false
                                    actions.query("group:$group")
                                },
                                enabled = !state.busy,
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                state.query,
                actions.query,
                label = { Text(stringResource(R.string.search_book_source)) },
                singleLine = true,
                enabled = !state.busy,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .testTag("source-manager-search"),
            )
            if (state.showStatus) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(
                            "" to stringResource(R.string.all),
                            BookSourceCheckState.NEEDS_CHECK to
                                stringResource(R.string.source_check_needed),
                            BookSourceCheckState.PASSED to
                                stringResource(R.string.source_check_passed),
                            BookSourceCheckState.FAILED to
                                stringResource(R.string.source_check_failed),
                        )
                        .forEach { (status, label) ->
                            TextButton({ actions.status(status) }, enabled = !state.busy) {
                                Text((if (state.status == status) "✓ " else "") + label)
                            }
                        }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { message ->
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton({ actions.action("retry", "") }, enabled = !state.busy) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            state.checkMessage?.let { message ->
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        Modifier.weight(1f),
                        modifier = Modifier.testTag("source-manager-check-progress"),
                    )
                    TextButton({ actions.action("cancel-check", "") }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("source-manager-list"),
            ) {
                itemsIndexed(state.rows, key = { _, row -> row.url }) { index, row ->
                    if (state.byDomain && (index == 0 || state.rows[index - 1].host != row.host)) {
                        Text(
                            row.host,
                            Modifier.padding(8.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    SourceManagerItem(
                        row = row,
                        state = state,
                        actions = actions,
                        selectionGesture =
                            sourceManagerDragModifier(row.url, true, listState, state, actions),
                        reorderGesture =
                            sourceManagerDragModifier(row.url, false, listState, state, actions),
                    )
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(actions.selectAll, enabled = !state.busy) {
                    Text(stringResource(R.string.select_all))
                }
                TextButton(actions.invert, enabled = !state.busy) {
                    Text(stringResource(R.string.revert_selection))
                }
                Text(
                    "$selectedCount/${state.rows.size}",
                    Modifier.weight(1f).testTag("source-manager-count"),
                )
                TextButton(
                    { actions.action("delete", "") },
                    enabled = selectedCount > 0 && !state.busy,
                ) {
                    Text(stringResource(R.string.delete))
                }
                var selectionMenu by remember { mutableStateOf(false) }
                Box {
                    TextButton(
                        { selectionMenu = true },
                        enabled = selectedCount > 0 && !state.busy,
                    ) {
                        Text(stringResource(R.string.menu))
                    }
                    DropdownMenu(
                        selectionMenu,
                        { selectionMenu = false },
                        Modifier.heightIn(max = 450.dp),
                    ) {
                        SourceMutation.entries
                            .filter { it != SourceMutation.DELETE }
                            .forEach { mutation ->
                                DropdownMenuItem(
                                    text = { Text(mutationName(mutation)) },
                                    onClick = {
                                        selectionMenu = false
                                        actions.mutation(mutation, null)
                                    },
                                )
                            }
                        listOf(
                                stringResource(R.string.check_select_source) to "check",
                                stringResource(R.string.export_selection) to "export",
                                stringResource(R.string.share_selected_source) to "share",
                                stringResource(R.string.check_selected_interval) to "interval",
                            )
                            .forEach { (label, action) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectionMenu = false
                                        actions.action(action, "")
                                    },
                                )
                            }
                    }
                }
            }
        }
    }
    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) actions.dismiss() },
            title = { Text(dialogName(dialog)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (dialog == SourceManagerDialog.DELETE) {
                        Text(stringResource(R.string.sure_del))
                    } else {
                        OutlinedTextField(
                            state.draft,
                            actions.draft,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("source-manager-draft"),
                        )
                        if (dialog == SourceManagerDialog.IMPORT) {
                            state.importHistory.forEach { value ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        { actions.draft(value) },
                                        Modifier.weight(1f),
                                        enabled = !state.busy,
                                    ) {
                                        Text(value)
                                    }
                                    TextButton(
                                        { actions.forgetImport(value) },
                                        enabled = !state.busy,
                                    ) {
                                        Text(stringResource(R.string.delete))
                                    }
                                }
                            }
                        }
                        if (dialog == SourceManagerDialog.CHECK)
                            TextButton({ actions.action("check-config", "") }) {
                                Text(stringResource(R.string.check_source_config))
                            }
                        if (
                            dialog == SourceManagerDialog.ADD_GROUP ||
                                dialog == SourceManagerDialog.REMOVE_GROUP
                        ) {
                            state.groups.forEach { group ->
                                TextButton({ actions.draft(group) }, enabled = !state.busy) {
                                    Text(group)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    actions.confirm,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("source-manager-confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(actions.dismiss, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SourceManagerItem(
    row: SourceManagerRow,
    state: SourceManagerState,
    actions: SourceManagerActions,
    selectionGesture: Modifier,
    reorderGesture: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(4.dp).testTag("source-manager-row:${row.url}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(selectionGesture) {
            Checkbox(
                row.url in state.selected,
                { actions.toggle(row.url) },
                enabled = !state.busy,
                modifier = Modifier.semantics { contentDescription = row.displayName },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(row.displayName)
            Text(
                stringResource(R.string.source_bookshelf_count, state.counts[row.url] ?: 0),
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                if (row.hasJs)
                    Text(
                        stringResource(R.string.js_source_badge),
                        style = MaterialTheme.typography.labelSmall,
                    )
                if (row.hasExplore)
                    Text(
                        stringResource(
                            if (row.exploreEnabled) R.string.tag_explore_enabled
                            else R.string.tag_explore_disabled
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
            val debugMessage = state.debugMessages[row.url].orEmpty()
            if (state.showStatus || debugMessage.isNotEmpty()) {
                val statusLabel =
                    stringResource(
                        when (row.checkStatus) {
                            BookSourceCheckState.PASSED -> R.string.source_check_passed
                            BookSourceCheckState.FAILED -> R.string.source_check_failed
                            else -> R.string.source_check_needed
                        }
                    )
                Text(
                    debugMessage.ifEmpty {
                        statusLabel +
                            row.checkDetail.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (debugMessage.isNotEmpty() && !Regex("成功|失败").containsMatchIn(debugMessage))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Switch(
            row.enabled,
            {
                actions.mutation(if (it) SourceMutation.ENABLE else SourceMutation.DISABLE, row.url)
            },
            enabled = !state.busy,
            modifier =
                Modifier.testTag("source-manager-enabled:${row.url}").semantics {
                    contentDescription = row.displayName
                },
        )
        TextButton({ actions.action("edit", row.url) }, enabled = !state.busy) {
            Text(stringResource(R.string.edit))
        }
        Box {
            TextButton(
                { menuOpen = true },
                enabled = !state.busy,
                modifier = reorderGesture.width(48.dp).testTag("source-manager-drag:${row.url}"),
            ) {
                Text("⋮")
            }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                val top = stringResource(R.string.to_top)
                val bottom = stringResource(R.string.to_bottom)
                val login = stringResource(R.string.login)
                val search = stringResource(R.string.search)
                val debug = stringResource(R.string.debug)
                val delete = stringResource(R.string.delete)
                val explore =
                    stringResource(
                        if (row.exploreEnabled) R.string.disable_explore
                        else R.string.enable_explore
                    )
                val options = buildList {
                    if (state.canMove) {
                        add(top to "top")
                        add(bottom to "bottom")
                        add("↑" to "up")
                        add("↓" to "down")
                    }
                    if (row.hasLogin) add(login to "login")
                    add(search to "search")
                    add(debug to "debug")
                    add(delete to "delete")
                    if (row.hasExplore) add(explore to "explore")
                }
                options.forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            actions.action(action, row.url)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun managerMenu(state: SourceManagerState): List<Pair<String, String>> =
    listOf(
        stringResource(R.string.add_book_source) to "add",
        stringResource(R.string.js_source_new) to "add-js",
        stringResource(R.string.import_by_qr_code) to "qr",
        stringResource(R.string.import_local) to "local",
        stringResource(R.string.import_on_line) to "online",
        stringResource(R.string.group_manage) to "groups",
        stringResource(R.string.enabled) to "filter-enabled",
        stringResource(R.string.disabled) to "filter-disabled",
        stringResource(R.string.need_login) to "filter-login",
        stringResource(R.string.no_group) to "filter-no-group",
        stringResource(R.string.enabled_explore) to "filter-explore",
        stringResource(R.string.disabled_explore) to "filter-no-explore",
        checkedLabel(stringResource(R.string.sort_desc), !state.ascending) to "descending",
        checkedLabel(stringResource(R.string.group_sources_by_domain), state.byDomain) to "domain",
        checkedLabel(stringResource(R.string.show_source_check_status), state.showStatus) to
            "show-status",
        checkedLabel(stringResource(R.string.block_source_navigation), state.blockNavigation) to
            "block-navigation",
        stringResource(R.string.refresh) to "retry",
        stringResource(R.string.help) to "help",
    )

private fun checkedLabel(label: String, checked: Boolean): String =
    if (checked) "✓ $label" else label

@Composable
private fun mutationName(action: SourceMutation): String =
    stringResource(
        when (action) {
            SourceMutation.DELETE -> R.string.delete
            SourceMutation.ENABLE -> R.string.enable_selection
            SourceMutation.DISABLE -> R.string.disable_selection
            SourceMutation.ENABLE_EXPLORE -> R.string.enable_explore
            SourceMutation.DISABLE_EXPLORE -> R.string.disable_explore
            SourceMutation.TOP -> R.string.selection_to_top
            SourceMutation.BOTTOM -> R.string.selection_to_bottom
            SourceMutation.ADD_GROUP -> R.string.add_group
            SourceMutation.REMOVE_GROUP -> R.string.remove_group
        }
    )

@Composable
private fun dialogName(dialog: SourceManagerDialog): String =
    stringResource(
        when (dialog) {
            SourceManagerDialog.DELETE -> R.string.delete
            SourceManagerDialog.ADD_GROUP -> R.string.add_group
            SourceManagerDialog.REMOVE_GROUP -> R.string.remove_group
            SourceManagerDialog.IMPORT -> R.string.import_on_line
            SourceManagerDialog.CHECK -> R.string.search_book_key
        }
    )

@Composable
private fun sortName(sort: BookSourceSort): String =
    stringResource(
        when (sort) {
            BookSourceSort.Default -> R.string.sort_manual
            BookSourceSort.Weight -> R.string.sort_auto
            BookSourceSort.Name -> R.string.sort_by_name
            BookSourceSort.Url -> R.string.sort_by_url
            BookSourceSort.Update -> R.string.sort_by_lastUpdateTime
            BookSourceSort.Respond -> R.string.sort_by_respondTime
            BookSourceSort.Enable -> R.string.is_enabled
        }
    )

/** Both gestures share edge scrolling; selection uses a snapshot to reverse on returning. */
@Composable
private fun sourceManagerDragModifier(
    key: String,
    selecting: Boolean,
    listState: LazyListState,
    state: SourceManagerState,
    actions: SourceManagerActions,
): Modifier {
    val gestureScope = rememberCoroutineScope()
    val latestState by rememberUpdatedState(state)
    return Modifier.pointerInput(key, state.busy, state.canMove) {
        var active = false
        var pointerY = 0f
        var anchor = 0
        var selectedAtStart = emptySet<String>()
        var scrollJob: Job? = null
        fun updateTarget() {
            if (!active) return
            val target =
                listState.layoutInfo.visibleItemsInfo.lastOrNull { pointerY >= it.offset }?.index
                    ?: anchor
            if (selecting) {
                val keys =
                    (minOf(anchor, target)..maxOf(anchor, target))
                        .mapNotNull {
                            latestState.rows.getOrNull(it)?.url
                        }
                        .toSet()
                actions.slide(keys, selectedAtStart)
            } else {
                actions.previewDrag(target)
            }
        }
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    active = !latestState.busy && (selecting || latestState.canMove)
                    if (active) {
                        anchor = latestState.rows.indexOfFirst { it.url == key }
                        pointerY =
                            (listState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.key == key }
                                ?.offset ?: 0) + position.y
                        selectedAtStart = latestState.selected.toSet()
                        if (selecting) actions.slide(setOf(key), selectedAtStart)
                        else actions.beginDrag(key)
                        scrollJob = gestureScope.launch {
                            while (true) {
                                val viewport = listState.layoutInfo
                                val scroll =
                                    when {
                                        pointerY < viewport.viewportStartOffset + 48 -> -24f
                                        pointerY > viewport.viewportEndOffset - 48 -> 24f
                                        else -> 0f
                                    }
                                if (scroll != 0f) {
                                    listState.scrollBy(scroll)
                                    updateTarget()
                                }
                                delay(32)
                            }
                        }
                    }
                },
                onDrag = { change, amount ->
                    if (active) {
                        change.consume()
                        pointerY += amount.y
                        updateTarget()
                    }
                },
                onDragEnd = {
                    scrollJob?.cancel()
                    if (active && !selecting) actions.finishDrag(false)
                    active = false
                },
                onDragCancel = {
                    scrollJob?.cancel()
                    if (active && !selecting) actions.finishDrag(true)
                    active = false
                },
            )
        } finally {
            scrollJob?.cancel()
            if (active && !selecting) actions.finishDrag(true)
        }
    }
}
