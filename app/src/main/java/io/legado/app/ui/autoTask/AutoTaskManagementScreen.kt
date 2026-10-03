package io.legado.app.ui.autoTask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R

class AutoTaskManagementActions(
    val action: (AutoTaskManagementAction, String?) -> Unit = { _, _ -> },
    val query: (String) -> Unit = {},
    val select: (String) -> Unit = {},
    val selectAll: () -> Unit = {},
    val invert: () -> Unit = {},
    val enabled: (String, Boolean) -> Unit = { _, _ -> },
    val selectedEnabled: (Boolean) -> Unit = {},
    val move: (String, Int) -> Unit = { _, _ -> },
    val askDelete: (String?) -> Unit = {},
    val delete: () -> Unit = {},
    val closeDelete: () -> Unit = {},
    val showLog: (String) -> Unit = {},
    val clearLog: () -> Unit = {},
    val closeLog: () -> Unit = {},
    val openCron: () -> Unit = {},
    val cronDraft: (String) -> Unit = {},
    val saveCron: () -> Unit = {},
    val closeCron: () -> Unit = {},
    val openOnline: () -> Unit = {},
    val onlineInput: (String) -> Unit = {},
    val importOnline: () -> Unit = {},
    val closeOnline: () -> Unit = {},
    val removeHistory: (String) -> Unit = {},
    val export: (Boolean) -> Unit = {},
    val copyExport: (Boolean) -> Unit = {},
    val closeExport: () -> Unit = {},
    val retry: () -> Unit = {},
    val beginSlide: (String) -> Unit = {},
    val slideTo: (String) -> Unit = {},
    val endSlide: () -> Unit = {},
    val cancelSlide: () -> Unit = {},
)

@Composable
fun AutoTaskManagementScreen(state: AutoTaskManagementState, actions: AutoTaskManagementActions) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var batchMenu by rememberSaveable { mutableStateOf(false) }
    val backLabel = stringResource(R.string.back)
    val moreLabel = stringResource(R.string.more_menu)
    val enableLabel = stringResource(R.string.enable)
    val list = rememberLazyListState()
    val drag = rememberAutoTaskSlide(list, actions)
    Surface {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    { actions.action(AutoTaskManagementAction.Close, null) },
                    Modifier.testTag("tasks-back").semantics { contentDescription = backLabel },
                ) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    stringResource(R.string.auto_task_manage),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    { actions.action(AutoTaskManagementAction.Edit, null) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("tasks-add"),
                ) {
                    Text(stringResource(R.string.auto_task_add))
                }
                Box {
                    TextButton(
                        { menu = true },
                        Modifier.testTag("tasks-menu").semantics { contentDescription = moreLabel },
                    ) {
                        Text("⋮")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        fun close(action: () -> Unit) {
                            menu = false
                            action()
                        }
                        DropdownMenuItem(
                            { Text(stringResource(R.string.import_local)) },
                            {
                                close { actions.action(AutoTaskManagementAction.ImportLocal, null) }
                            },
                            enabled = !state.busy,
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.import_on_line)) },
                            { close(actions.openOnline) },
                            enabled = !state.busy,
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.export)) },
                            { close { actions.export(false) } },
                            enabled = !state.busy,
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.help)) },
                            { close { actions.action(AutoTaskManagementAction.Help, null) } },
                        )
                    }
                }
            }
            OutlinedTextField(
                state.query,
                actions.query,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("tasks-search"),
                label = { Text(stringResource(R.string.search)) },
                singleLine = true,
                enabled = !state.busy,
            )
            state.error?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (error == "EmptyImport") stringResource(R.string.wrong_format)
                        else error,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(actions.retry) { Text(stringResource(R.string.retry)) }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .testTag("tasks-list")
                    .autoTaskSlideSelection(list, drag, !state.busy, actions),
                state = list,
            ) {
                if (!state.loading && state.visible.isEmpty())
                    item {
                        Text(
                            stringResource(R.string.content_empty),
                            Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                items(state.visible, key = { it.id }) { task ->
                    var rowMenu by rememberSaveable(task.id) { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !state.busy) {
                                actions.action(AutoTaskManagementAction.Edit, task.id)
                            }
                            .testTag("task-" + task.id)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                task.id in state.selected,
                                { actions.select(task.id) },
                                enabled = !state.busy,
                                modifier =
                                    Modifier.testTag("task-select-" + task.id).semantics {
                                        contentDescription = task.name
                                    },
                            )
                            Text(
                                task.name,
                                Modifier.weight(1f).clickable(enabled = !state.busy) {
                                    actions.select(task.id)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Switch(
                                task.enabled,
                                { actions.enabled(task.id, it) },
                                enabled = !state.busy,
                                modifier =
                                    Modifier.testTag("task-enabled-" + task.id).semantics {
                                        contentDescription = enableLabel
                                    },
                            )
                            Box {
                                TextButton(
                                    { rowMenu = true },
                                    Modifier.testTag("task-menu-" + task.id).semantics {
                                        contentDescription = moreLabel
                                    },
                                ) {
                                    Text("⋮")
                                }
                                DropdownMenu(rowMenu, { rowMenu = false }) {
                                    fun close(action: () -> Unit) {
                                        rowMenu = false
                                        action()
                                    }
                                    if (task.hasLogin)
                                        DropdownMenuItem(
                                            { Text(stringResource(R.string.login)) },
                                            {
                                                close {
                                                    actions.action(
                                                        AutoTaskManagementAction.Login,
                                                        task.id,
                                                    )
                                                }
                                            },
                                        )
                                    DropdownMenuItem(
                                        { Text(stringResource(R.string.auto_task_log)) },
                                        { close { actions.showLog(task.id) } },
                                    )
                                    DropdownMenuItem(
                                        { Text(stringResource(R.string.auto_task_move_up)) },
                                        { close { actions.move(task.id, -1) } },
                                        enabled =
                                            !state.busy &&
                                                state.visible.firstOrNull()?.id != task.id,
                                    )
                                    DropdownMenuItem(
                                        { Text(stringResource(R.string.auto_task_move_down)) },
                                        { close { actions.move(task.id, 1) } },
                                        enabled =
                                            !state.busy &&
                                                state.visible.lastOrNull()?.id != task.id,
                                    )
                                    DropdownMenuItem(
                                        {
                                            Text(
                                                stringResource(R.string.delete),
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        { close { actions.askDelete(task.id) } },
                                        enabled = !state.busy,
                                    )
                                }
                            }
                        }
                        Text(
                            task.summary,
                            Modifier.padding(start = 48.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(
                                { actions.action(AutoTaskManagementAction.Debug, task.id) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("task-debug-" + task.id),
                            ) {
                                Text(stringResource(R.string.debug))
                            }
                            TextButton(
                                { actions.action(AutoTaskManagementAction.Edit, task.id) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("task-edit-" + task.id),
                            ) {
                                Text(stringResource(R.string.edit))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    state.allSelected,
                    { actions.selectAll() },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("tasks-all"),
                )
                Text(
                    "${state.selection.size}/${state.visible.size}",
                    Modifier.weight(1f).testTag("tasks-count"),
                )
                TextButton(actions.invert, enabled = !state.busy) {
                    Text(stringResource(R.string.revert_selection))
                }
                TextButton(
                    { actions.askDelete(null) },
                    enabled = !state.busy && state.selection.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.delete))
                }
                Box {
                    TextButton(
                        { batchMenu = true },
                        Modifier.testTag("tasks-batch"),
                        enabled = !state.busy && state.selection.isNotEmpty(),
                    ) {
                        Text("⋮")
                    }
                    DropdownMenu(batchMenu, { batchMenu = false }) {
                        fun close(action: () -> Unit) {
                            batchMenu = false
                            action()
                        }
                        DropdownMenuItem(
                            { Text(stringResource(R.string.auto_task_batch_cron)) },
                            { close(actions.openCron) },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.enable_selection)) },
                            { close { actions.selectedEnabled(true) } },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.disable_selection)) },
                            { close { actions.selectedEnabled(false) } },
                        )
                        DropdownMenuItem(
                            { Text(stringResource(R.string.export_selection)) },
                            { close { actions.export(true) } },
                        )
                    }
                }
            }
        }
    }
    state.deleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = actions.closeDelete,
            title = { Text(stringResource(R.string.delete)) },
            text = {
                val name =
                    if (ids.size == 1)
                        state.items.firstOrNull { it.id == ids.first() }?.name.orEmpty()
                    else ids.size.toString()
                Text(stringResource(R.string.auto_task_delete_confirm, name))
            },
            confirmButton = {
                TextButton(
                    actions.delete,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("tasks-delete-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.closeDelete, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.logId?.let {
        AlertDialog(
            onDismissRequest = actions.closeLog,
            title = { Text(stringResource(R.string.auto_task_log)) },
            text = {
                SelectionContainer {
                    Text(
                        state.logItem?.log ?: stringResource(R.string.auto_task_no_log),
                        Modifier.heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("tasks-log"),
                    )
                }
            },
            confirmButton = { TextButton(actions.closeLog) { Text(stringResource(R.string.ok)) } },
            dismissButton = {
                TextButton(
                    actions.clearLog,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("tasks-log-clear"),
                ) {
                    Text(stringResource(R.string.clear))
                }
            },
        )
    }
    state.cronIds?.let {
        AlertDialog(
            onDismissRequest = actions.closeCron,
            title = { Text(stringResource(R.string.auto_task_batch_cron)) },
            text = {
                Column {
                    OutlinedTextField(
                        state.cronDraft,
                        actions.cronDraft,
                        Modifier.fillMaxWidth().testTag("tasks-cron"),
                        enabled = !state.busy,
                        singleLine = true,
                        isError = state.cronInvalid,
                        label = { Text(stringResource(R.string.auto_task_cron_hint)) },
                    )
                    if (state.cronInvalid)
                        Text(
                            stringResource(R.string.auto_task_cron_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    actions.saveCron,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("tasks-cron-save"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.closeCron, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.online)
        AlertDialog(
            onDismissRequest = actions.closeOnline,
            title = { Text(stringResource(R.string.import_on_line)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (state.onlineLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedTextField(
                        state.onlineInput,
                        actions.onlineInput,
                        Modifier.fillMaxWidth().testTag("tasks-online-input"),
                        label = { Text(stringResource(R.string.add_url)) },
                        enabled = !state.busy,
                        minLines = 2,
                        maxLines = 6,
                    )
                    state.history.forEach { url ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                url,
                                Modifier.weight(1f)
                                    .clickable(enabled = !state.busy) { actions.onlineInput(url) }
                                    .padding(8.dp),
                            )
                            TextButton(
                                { actions.removeHistory(url) },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("tasks-history-delete-" + url),
                            ) {
                                Text(stringResource(R.string.delete))
                            }
                        }
                    }
                    state.error?.let {
                        Text(
                            if (it == "EmptyImport") stringResource(R.string.wrong_format) else it,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    actions.importOnline,
                    enabled = !state.busy && !state.onlineLoading,
                    modifier = Modifier.testTag("tasks-online-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.closeOnline, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    state.exportNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = actions.closeExport,
            title = { Text(stringResource(R.string.export_success)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (notice.summary.isNotEmpty()) Text(notice.summary)
                    SelectionContainer {
                        Text(
                            notice.url,
                            Modifier.testTag("tasks-export-url").padding(vertical = 12.dp),
                        )
                    }
                    if (notice.passphrase != null) SelectionContainer { Text(notice.passphrase) }
                    if (notice.passphrase != null)
                        TextButton(
                            { actions.copyExport(true) },
                            Modifier.testTag("tasks-copy-passphrase"),
                        ) {
                            Text(stringResource(R.string.shibboleth))
                        }
                }
            },
            confirmButton = {
                TextButton({ actions.copyExport(false) }, Modifier.testTag("tasks-copy-export")) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.closeExport) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
