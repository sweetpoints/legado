package io.legado.app.ui.association

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.RssImportPreferences
import io.legado.app.data.repository.RssImportStatus

internal enum class RssImportMenu(val label: Int) {
    KeepName(R.string.keep_original_name),
    KeepGroup(R.string.keep_group),
    KeepEnable(R.string.keep_enable),
    ShowComment(R.string.show_source_comment),
    RememberGroup(R.string.import_remember_group),
    Automatic(R.string.replace_source_on_import),
    Effective(R.string.effective_replaces),
    Manual(R.string.manual_replace_rule),
    ReplaceRules(R.string.menu_replace_rule),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RssImportScreen(
    state: RssImportUiState,
    onSearch: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectVisible: () -> Unit,
    onCode: (String) -> Unit,
    onExpand: (String) -> Unit,
    onMenu: (RssImportMenu) -> Unit,
    onGroup: () -> Unit,
    onGroupDraft: (String) -> Unit,
    onAddGroup: (Boolean) -> Unit,
    onAcceptGroup: () -> Unit,
    onCloseGroup: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels =
        RssImportSearchLabels(
            stringResource(R.string.enabled),
            stringResource(R.string.disabled),
            stringResource(R.string.need_login),
            stringResource(R.string.no_group),
        )
    val visible = visibleRssImportItems(state, labels)
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
                .imePadding()
        ) {
            TopAppBar(
                title = { Text(stringResource(R.string.import_rss_source)) },
                actions = {
                    Box {
                        IconButton(
                            { menuOpen = true },
                            enabled = state.interactive,
                            modifier = Modifier.testTag("rss-import-menu"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.menu),
                            )
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            RssImportMenu.entries.forEach { item ->
                                val checked =
                                    when (item) {
                                        RssImportMenu.KeepName -> state.preferences.keepName
                                        RssImportMenu.KeepGroup -> state.preferences.keepGroup
                                        RssImportMenu.KeepEnable -> state.preferences.keepEnable
                                        RssImportMenu.ShowComment -> state.preferences.showComment
                                        RssImportMenu.RememberGroup ->
                                            state.preferences.rememberGroup
                                        RssImportMenu.Automatic -> state.automatic
                                        else -> null
                                    }
                                DropdownMenuItem(
                                    text = { Text(stringResource(item.label)) },
                                    onClick = {
                                        menuOpen = false
                                        onMenu(item)
                                    },
                                    enabled = item != RssImportMenu.Manual || !state.automatic,
                                    modifier = Modifier.testTag("rss-import-menu-${item.name}"),
                                    trailingIcon = { checked?.let { Checkbox(it, null) } },
                                )
                            }
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(),
            )
            TextButton(
                onGroup,
                enabled = state.interactive,
                modifier = Modifier.fillMaxWidth().testTag("rss-import-group"),
            ) {
                Text(
                    if (state.group.isNullOrBlank()) stringResource(R.string.diy_source_group)
                    else
                        (if (state.addGroup) "+" else "") +
                            stringResource(R.string.diy_edit_source_group_title, state.group)
                )
            }
            OutlinedTextField(
                state.query,
                onSearch,
                singleLine = true,
                label = { Text(stringResource(R.string.search)) },
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .testTag("rss-import-search"),
            )
            if (state.loading || state.busy || state.pendingRefresh)
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("rss-import-progress"))
            LazyColumn(
                Modifier.weight(1f, fill = false).fillMaxWidth().testTag("rss-import-list")
            ) {
                state.error?.let { error ->
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            if (state.items.isEmpty())
                                TextButton(onRetry, enabled = !state.loading && !state.busy) {
                                    Text(stringResource(R.string.retry))
                                }
                        }
                    }
                }
                if (!state.loading && visible.isEmpty() && state.error == null)
                    item {
                        Text(
                            stringResource(
                                if (state.items.isEmpty()) R.string.wrong_format
                                else R.string.import_no_results
                            ),
                            Modifier.padding(16.dp),
                        )
                    }
                items(visible, key = { it.key }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("rss-import-row-${item.key}")
                            .clickable(enabled = state.interactive && item.canImport) {
                                onToggle(item.key)
                            }
                            .padding(horizontal = 8.dp)
                    ) {
                        Checkbox(
                            item.key in state.selected,
                            { onToggle(item.key) },
                            enabled = state.interactive && item.canImport,
                            modifier = Modifier.testTag("rss-import-check-${item.key}"),
                        )
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(item.sourceName, style = MaterialTheme.typography.bodyLarge)
                            val comment =
                                item.replacementError
                                    ?.takeIf { state.useReplacement }
                                    ?.let {
                                        stringResource(R.string.source_replacement_error, it)
                                    }
                                    ?: item.sourceComment?.takeIf {
                                        state.preferences.showComment && it.isNotBlank()
                                    }
                            comment?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = if (item.key in state.expanded) 39 else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.testTag("rss-import-comment-${item.key}")
                                            .clickable(enabled = state.interactive) {
                                                onExpand(item.key)
                                            },
                                )
                            }
                            Text(
                                stringResource(rssImportStatus(item.status)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(
                            { onCode(item.key) },
                            enabled = state.interactive,
                            modifier = Modifier.testTag("rss-import-code-${item.key}"),
                        ) {
                            Text(stringResource(R.string.open))
                        }
                    }
                }
            }
            TextButton(
                onSelectVisible,
                enabled = state.interactive && visible.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("rss-import-select-visible"),
            ) {
                val all = visible.all { !it.canImport || it.key in state.selected }
                Text(
                    if (state.query.isNotEmpty())
                        stringResource(
                            if (all) R.string.import_unselect_results
                            else R.string.import_select_results,
                            visible.count { it.key in state.selected },
                            visible.size,
                            state.selectCount,
                        )
                    else
                        stringResource(
                            if (state.isSelectAll) R.string.select_cancel_count
                            else R.string.select_all_count,
                            state.selectCount,
                            state.items.size,
                        )
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onCancel,
                    enabled = !state.busy && !state.pendingRefresh,
                    modifier = Modifier.testTag("rss-import-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onConfirm,
                    enabled = state.interactive,
                    modifier = Modifier.testTag("rss-import-confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        }
    }
    if (state.groupOpen && !state.finished)
        AlertDialog(
            onDismissRequest = onCloseGroup,
            title = { Text(stringResource(R.string.diy_edit_source_group)) },
            text = {
                Column(
                    Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.add_group))
                            Text(
                                stringResource(R.string.custom_group_summary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            state.addGroupDraft,
                            onAddGroup,
                            Modifier.testTag("rss-import-add-group"),
                        )
                    }
                    OutlinedTextField(
                        state.groupDraft,
                        onGroupDraft,
                        label = { Text(stringResource(R.string.group_name)) },
                        modifier = Modifier.fillMaxWidth().testTag("rss-import-group-name"),
                        singleLine = true,
                    )
                    LazyColumn(Modifier.heightIn(max = 180.dp)) {
                        items(
                            state.groups.filter { it.contains(state.groupDraft, ignoreCase = true) }
                        ) { group ->
                            TextButton({ onGroupDraft(group) }, Modifier.fillMaxWidth()) {
                                Text(group)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onAcceptGroup, Modifier.testTag("rss-import-group-ok")) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onCloseGroup, Modifier.testTag("rss-import-group-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}

internal fun rssImportMenuPreferences(
    menu: RssImportMenu,
    value: RssImportUiState,
): RssImportPreferences =
    when (menu) {
        RssImportMenu.KeepName -> value.preferences.copy(keepName = !value.preferences.keepName)
        RssImportMenu.KeepGroup -> value.preferences.copy(keepGroup = !value.preferences.keepGroup)
        RssImportMenu.KeepEnable ->
            value.preferences.copy(keepEnable = !value.preferences.keepEnable)
        RssImportMenu.ShowComment ->
            value.preferences.copy(showComment = !value.preferences.showComment)
        RssImportMenu.RememberGroup ->
            value.preferences.copy(
                rememberGroup = !value.preferences.rememberGroup,
                lastGroup = value.group,
                lastGroupAdd = value.addGroup,
            )
        else -> value.preferences
    }

internal fun rssImportStatus(status: RssImportStatus): Int =
    when (status) {
        RssImportStatus.New -> R.string.import_status_new
        RssImportStatus.Update -> R.string.import_status_update
        RssImportStatus.Existing -> R.string.import_status_exist
        RssImportStatus.Error -> R.string.import_status_error
    }
