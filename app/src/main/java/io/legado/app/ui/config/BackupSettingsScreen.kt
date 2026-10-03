package io.legado.app.ui.config

import android.text.format.Formatter
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.backup.*
import kotlinx.coroutines.launch

internal data class BackupEditorActions(
    val boolean: (BackupSettingSwitch, Boolean) -> Unit,
    val form: (BackupForm) -> Unit,
    val text: (String) -> Unit,
    val automaticEnabled: (Boolean) -> Unit,
    val automaticWebDav: (Boolean) -> Unit,
    val interval: (String) -> Unit,
    val choice: (String, Boolean) -> Unit,
    val confirm: () -> Unit,
    val dismiss: () -> Unit,
    val retry: () -> Unit,
)

internal data class BackupTaskActions(
    val path: () -> Unit,
    val backup: () -> Unit,
    val restore: () -> Unit,
    val localRestore: () -> Unit,
    val lan: () -> Unit,
    val help: () -> Unit,
    val log: () -> Unit,
    val importOld: () -> Unit,
    val selectPath: () -> Unit,
    val defaultPath: () -> Unit,
    val destination: (Boolean) -> Unit,
    val sendConfirm: () -> Unit,
    val send: () -> Unit,
    val scan: () -> Unit,
    val receive: () -> Unit,
    val selectRestore: (String) -> Unit,
    val dismiss: () -> Unit,
    val cancel: () -> Unit,
    val retry: () -> Unit,
    val retryConfirmed: () -> Unit,
    val closeOffer: () -> Unit,
)

private data class BackupRow(
    val key: String,
    val title: String,
    val summary: String = "",
    val category: String = "",
    val boolean: BackupSettingSwitch? = null,
    val form: BackupForm? = null,
    val heading: Boolean = false,
)

@Composable
private fun backupRows(settings: BackupSettingsSnapshot): List<BackupRow> {
    val web = stringResource(R.string.web_dav_set)
    val backup = stringResource(R.string.backup_restore)
    val rows = mutableListOf(BackupRow("web-category", web, category = web, heading = true))
    BackupSettingText.entries.forEach { key ->
        val title =
            stringResource(
                when (key) {
                    BackupSettingText.Url -> R.string.web_dav_url
                    BackupSettingText.Account -> R.string.web_dav_account
                    BackupSettingText.Password -> R.string.web_dav_pw
                    BackupSettingText.Directory -> R.string.sub_dir
                    BackupSettingText.Device -> R.string.webdav_device_name
                }
            )
        val text = settings.texts.getValue(key)
        val summary =
            when (key) {
                BackupSettingText.Url -> text.ifBlank { stringResource(R.string.web_dav_url_s) }
                BackupSettingText.Account ->
                    text.ifBlank { stringResource(R.string.web_dav_account_s) }
                BackupSettingText.Password ->
                    if (text.isEmpty()) stringResource(R.string.web_dav_pw_s)
                    else "*".repeat(text.length)
                else -> text
            }
        rows += BackupRow(key.key, title, summary, web, form = BackupForm.valueOf(key.name))
    }
    listOf(
            BackupSettingSwitch.BookRestore,
            BackupSettingSwitch.Progress,
            BackupSettingSwitch.ProgressPlus,
        )
        .forEach { key ->
            rows +=
                BackupRow(
                    key.key,
                    stringResource(
                        when (key) {
                            BackupSettingSwitch.BookRestore -> R.string.webdav_book_auto_restore_t
                            BackupSettingSwitch.Progress -> R.string.sync_book_progress_t
                            else -> R.string.sync_book_progress_plus_t
                        }
                    ),
                    stringResource(
                        when (key) {
                            BackupSettingSwitch.BookRestore -> R.string.webdav_book_auto_restore_s
                            BackupSettingSwitch.Progress -> R.string.sync_book_progress_s
                            else -> R.string.sync_book_progress_plus_s
                        }
                    ),
                    web,
                    boolean = key,
                )
        }
    rows += BackupRow("backup-category", backup, category = backup, heading = true)
    rows +=
        BackupRow(
            "localPassword",
            stringResource(R.string.set_local_password),
            stringResource(R.string.set_local_password_summary),
            backup,
            form = BackupForm.LocalPassword,
        )
    rows +=
        BackupRow(
            "backupUri",
            stringResource(R.string.backup_path),
            settings.backupPath?.takeIf { it.isNotBlank() }
                ?: "${stringResource(R.string.default_path)}\n${settings.defaultPath}",
            backup,
        )
    rows +=
        BackupRow(
            "backupContent",
            stringResource(R.string.backup_content),
            stringResource(R.string.backup_content_summary),
            backup,
            form = BackupForm.Content,
        )
    rows +=
        BackupRow(
            "lan_backup_transfer",
            stringResource(R.string.lan_backup_transfer),
            stringResource(R.string.lan_backup_transfer_summary),
            backup,
        )
    rows +=
        BackupRow(
            "web_dav_backup",
            stringResource(R.string.backup),
            stringResource(R.string.backup_destination_summary),
            backup,
        )
    rows +=
        BackupRow(
            "web_dav_restore",
            stringResource(R.string.restore),
            stringResource(R.string.restore_summary),
            backup,
        )
    rows +=
        BackupRow(
            "restoreIgnore",
            stringResource(R.string.restore_ignore),
            stringResource(R.string.restore_ignore_summary),
            backup,
            form = BackupForm.Ignore,
        )
    rows +=
        BackupRow(
            BackupSettingSwitch.Latest.key,
            stringResource(R.string.only_latest_backup_t),
            stringResource(R.string.only_latest_backup_s),
            backup,
            boolean = BackupSettingSwitch.Latest,
        )
    val automatic = settings.automatic
    rows +=
        BackupRow(
            "autoBackup",
            stringResource(R.string.auto_backup_t),
            if (automatic.enabled)
                stringResource(
                    R.string.auto_backup_destination_summary,
                    stringResource(
                        if (automatic.webDav) R.string.backup_local_webdav
                        else R.string.backup_local_only
                    ),
                    automatic.intervalDays,
                )
            else stringResource(R.string.auto_backup_disabled),
            backup,
            form = BackupForm.Automatic,
        )
    rows +=
        BackupRow(
            BackupSettingSwitch.CheckNew.key,
            stringResource(R.string.auto_check_new_backup_t),
            stringResource(R.string.auto_check_new_backup_s),
            backup,
            boolean = BackupSettingSwitch.CheckNew,
        )
    return rows
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BackupSettingsScreen(
    state: BackupSettingsState,
    runtime: BackupRuntimeState,
    editor: BackupEditorActions,
    tasks: BackupTaskActions,
    qr: ImageBitmap? = null,
    imageError: String? = null,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val settings = state.settings ?: BackupSettingsSnapshot()
    val rows = backupRows(settings)
    val enabled =
        !state.loading &&
            !state.failed &&
            !state.busy &&
            !state.taskBusy &&
            !state.pendingCommit &&
            !state.draftFailed &&
            runtime.event == null
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val errors = listOfNotNull(state.error, runtime.error).distinct()
    val found =
        search
            ?.let { query ->
                rows.filter {
                    !it.heading &&
                        configPreferenceMatches(query, it.title, it.summary, listOf(it.category))
                }
            }
            .orEmpty()
    LaunchedEffect(search, state.loading, state.failed) {
        if (search != null && !state.loading && !state.failed && found.isEmpty()) searchEmpty()
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    tasks.help,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("backup-help"),
                ) {
                    Text(stringResource(R.string.help))
                }
                var expanded by remember { mutableStateOf(false) }
                TextButton(
                    { expanded = true },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("backup-menu"),
                ) {
                    Text(stringResource(R.string.more_menu))
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_import_old_version)) },
                        onClick = {
                            expanded = false
                            tasks.importOld()
                        },
                        modifier = Modifier.testTag("backup-import-old"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log)) },
                        onClick = {
                            expanded = false
                            tasks.log()
                        },
                        modifier = Modifier.testTag("backup-log"),
                    )
                }
            }
            LazyColumn(
                state = scroll,
                modifier = Modifier.weight(1f).testTag("backup-settings-list"),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                if (state.loading || state.busy)
                    item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (errors.isNotEmpty())
                    item {
                        Column(Modifier.padding(16.dp)) {
                            errors.forEach {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("backup-error"),
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (runtime.error != null) tasks.retry() else editor.retry()
                                },
                                enabled = !state.loading && !state.busy && !runtime.busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("backup-retry"),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                items(rows, key = { it.key }) { row ->
                    val canEdit = enabled && (row.boolean?.let(settings::enabled) ?: true)
                    val base =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("backup-row-${row.key}")
                    val click = {
                        when {
                            row.form != null -> editor.form(row.form)
                            else ->
                                when (row.key) {
                                    "backupUri" -> tasks.path()
                                    "web_dav_backup" -> tasks.backup()
                                    "web_dav_restore" -> tasks.restore()
                                    "lan_backup_transfer" -> tasks.lan()
                                    else -> Unit
                                }
                        }
                    }
                    val input =
                        when {
                            row.boolean != null ->
                                base.toggleable(
                                    settings.switches.getValue(row.boolean),
                                    canEdit,
                                    Role.Switch,
                                ) {
                                    editor.boolean(row.boolean, it)
                                }
                            row.heading -> base
                            else ->
                                base.combinedClickable(
                                    enabled = canEdit,
                                    onClick = click,
                                    onLongClick =
                                        if (row.key == "web_dav_restore") tasks.localRestore
                                        else null,
                                )
                        }
                    Row(
                        input.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.title,
                                style =
                                    if (row.heading) MaterialTheme.typography.titleSmall
                                    else MaterialTheme.typography.bodyLarge,
                                color =
                                    if (row.heading) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                            if (row.summary.isNotEmpty())
                                Text(row.summary, style = MaterialTheme.typography.bodySmall)
                        }
                        row.boolean?.let {
                            Switch(
                                settings.switches.getValue(it),
                                onCheckedChange = null,
                                enabled = canEdit,
                            )
                        }
                    }
                }
            }
        }
        if (search != null && found.isNotEmpty())
            AlertDialog(
                onDismissRequest = searchFinished,
                title = { Text(search) },
                text = {
                    LazyColumn {
                        items(found, key = { it.key }) { row ->
                            Text(
                                "${row.category} > ${row.title}",
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("backup-search-${row.key}")
                                    .clickable {
                                        searchFinished()
                                        scope.launch {
                                            scroll.animateScrollToItem(
                                                rows.indexOf(row) +
                                                    (if (state.loading || state.busy) 1 else 0) +
                                                    (if (errors.isNotEmpty()) 1 else 0)
                                            )
                                        }
                                    }
                                    .padding(16.dp),
                            )
                        }
                    }
                },
                confirmButton = {},
            )
        state.draft?.form?.let { BackupFormDialog(it, state, editor) }
        BackupTaskDialogs(runtime, tasks, qr, imageError)
    }
}

@Composable
private fun BackupFormDialog(
    form: BackupForm,
    state: BackupSettingsState,
    actions: BackupEditorActions,
) {
    val draft = state.draft ?: return
    val enabled =
        !state.loading && !state.failed && !state.busy && !state.pendingCommit && !state.draftFailed
    val title =
        stringResource(
            when (form) {
                BackupForm.Url -> R.string.web_dav_url
                BackupForm.Account -> R.string.web_dav_account
                BackupForm.Password -> R.string.web_dav_pw
                BackupForm.Directory -> R.string.sub_dir
                BackupForm.Device -> R.string.webdav_device_name
                BackupForm.LocalPassword -> R.string.set_local_password
                BackupForm.Automatic -> R.string.auto_backup_t
                BackupForm.Content -> R.string.backup_content
                BackupForm.Ignore -> R.string.restore_ignore
            }
        )
    AlertDialog(
        onDismissRequest = { if (enabled) actions.dismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.failed || state.draftFailed || state.pendingCommit)
                    TextButton(
                        actions.retry,
                        enabled = !state.loading && !state.busy,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("backup-form-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                when (form) {
                    BackupForm.Content,
                    BackupForm.Ignore ->
                        state.choices.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("backup-choice-${row.key}")
                                    .toggleable(row.checked, enabled, Role.Checkbox) {
                                        actions.choice(row.key, it)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(row.checked, onCheckedChange = null, enabled = enabled)
                                Text(row.title, Modifier.weight(1f))
                            }
                        }
                    BackupForm.Automatic -> {
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("backup-auto-enabled")
                                .toggleable(
                                    value = draft.autoEnabled,
                                    enabled = enabled,
                                    role = Role.Switch,
                                    onValueChange = actions.automaticEnabled,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.auto_backup_t), Modifier.weight(1f))
                            Switch(draft.autoEnabled, null, enabled = enabled)
                        }
                        listOf(false, true).forEach { webDav ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag(
                                        if (webDav) "backup-auto-webdav" else "backup-auto-local"
                                    )
                                    .clickable(enabled = enabled) {
                                        actions.automaticWebDav(webDav)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(draft.autoWebDav == webDav, null, enabled = enabled)
                                Text(
                                    stringResource(
                                        if (webDav) R.string.backup_local_webdav
                                        else R.string.backup_local_only
                                    )
                                )
                            }
                        }
                        OutlinedTextField(
                            draft.intervalText,
                            actions.interval,
                            enabled = enabled,
                            singleLine = true,
                            isError = state.invalidInterval,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(stringResource(R.string.auto_backup_interval_days)) },
                            modifier = Modifier.fillMaxWidth().testTag("backup-auto-days"),
                        )
                        if (state.invalidInterval)
                            Text(
                                stringResource(R.string.auto_backup_interval_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                    }
                    else -> {
                        if (form == BackupForm.LocalPassword)
                            Text(stringResource(R.string.set_local_password_summary))
                        OutlinedTextField(
                            draft.text,
                            actions.text,
                            enabled = enabled,
                            singleLine = true,
                            visualTransformation =
                                if (form in listOf(BackupForm.Password, BackupForm.LocalPassword))
                                    PasswordVisualTransformation()
                                else VisualTransformation.None,
                            label = {
                                Text(if (form == BackupForm.LocalPassword) "password" else title)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("backup-text"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                actions.confirm,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("backup-form-ok"),
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(
                actions.dismiss,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("backup-form-cancel"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun BackupTaskDialogs(
    state: BackupRuntimeState,
    actions: BackupTaskActions,
    qr: ImageBitmap?,
    imageError: String?,
) {
    val context = LocalContext.current
    state.popup?.let { popup ->
        val title =
            stringResource(
                when (popup) {
                    BackupPopup.Path -> R.string.backup_path
                    BackupPopup.Destination -> R.string.backup
                    BackupPopup.Lan -> R.string.lan_backup_transfer
                    BackupPopup.SendConfirm -> R.string.lan_backup_send
                    BackupPopup.ReceiveConfirm -> R.string.lan_backup_receive
                    BackupPopup.RestoreFiles -> R.string.select_restore_file
                    BackupPopup.RestoreFallback -> R.string.restore
                    BackupPopup.RetryConfirm -> R.string.retry
                }
            )
        AlertDialog(
            onDismissRequest = actions.dismiss,
            title = { Text(title) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    @Composable
                    fun option(label: String, tag: String, click: () -> Unit) {
                        TextButton(
                            click,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
                        ) {
                            Text(label)
                        }
                    }
                    when (popup) {
                        BackupPopup.Path -> {
                            option(
                                stringResource(R.string.default_path),
                                "backup-path-default",
                                actions.defaultPath,
                            )
                            option(
                                stringResource(R.string.select_folder),
                                "backup-path-select",
                                actions.selectPath,
                            )
                        }
                        BackupPopup.Destination -> {
                            option(
                                stringResource(R.string.backup_local_only),
                                "backup-destination-local",
                            ) {
                                actions.destination(false)
                            }
                            option(
                                stringResource(R.string.backup_local_webdav),
                                "backup-destination-webdav",
                            ) {
                                actions.destination(true)
                            }
                        }
                        BackupPopup.Lan -> {
                            option(
                                stringResource(R.string.lan_backup_send),
                                "backup-lan-send",
                                actions.sendConfirm,
                            )
                            option(
                                stringResource(R.string.lan_backup_receive),
                                "backup-lan-receive",
                                actions.scan,
                            )
                        }
                        BackupPopup.SendConfirm ->
                            Text(stringResource(R.string.lan_backup_send_confirm))
                        BackupPopup.ReceiveConfirm ->
                            state.receive?.let {
                                Text(
                                    stringResource(
                                        R.string.lan_backup_receive_confirm,
                                        Formatter.formatFileSize(context, it.bytes),
                                        it.device,
                                    )
                                )
                            }
                        BackupPopup.RestoreFiles ->
                            state.names.forEach { name ->
                                option(name, "backup-restore-name-$name") {
                                    actions.selectRestore(name)
                                }
                            }
                        BackupPopup.RestoreFallback ->
                            Text("WebDavError\n${state.error.orEmpty()}\n将从本地备份恢复。")
                        BackupPopup.RetryConfirm -> Text("上次操作已中断，是否重新执行？")
                    }
                }
            },
            confirmButton = {
                val confirmed =
                    when (popup) {
                        BackupPopup.SendConfirm -> actions.send
                        BackupPopup.ReceiveConfirm -> actions.receive
                        BackupPopup.RestoreFallback -> actions.localRestore
                        BackupPopup.RetryConfirm -> actions.retryConfirmed
                        else -> null
                    }
                confirmed?.let {
                    TextButton(
                        it,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("backup-task-confirm"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    actions.dismiss,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("backup-task-cancel"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (state.busy)
        AlertDialog(
            onDismissRequest = actions.cancel,
            title = {
                Text(
                    when (state.waiting) {
                        BackupWait.Backup -> "备份中…"
                        BackupWait.Restore -> "恢复中…"
                        BackupWait.LanSend -> stringResource(R.string.lan_backup_send)
                        BackupWait.LanReceive -> stringResource(R.string.lan_backup_receive)
                        BackupWait.BeforeLanRestore -> stringResource(R.string.backup)
                        else -> stringResource(R.string.loading)
                    }
                )
            },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    actions.cancel,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("backup-task-stop"),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    state.offer?.let {
        AlertDialog(
            onDismissRequest = actions.closeOffer,
            title = { Text(stringResource(R.string.lan_backup_send)) },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (qr != null)
                        Image(
                            qr,
                            stringResource(R.string.lan_backup_waiting),
                            Modifier.fillMaxWidth().height(240.dp).testTag("backup-lan-qr"),
                        )
                    else if (imageError != null)
                        Text(imageError, color = MaterialTheme.colorScheme.error)
                    else CircularProgressIndicator()
                    Text(stringResource(R.string.lan_backup_waiting))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(actions.closeOffer, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
