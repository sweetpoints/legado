package io.legado.app.ui.config

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.settings.*
import io.legado.app.ui.widget.dialog.*
import kotlinx.coroutines.*

internal enum class OtherDestination {
    CheckSource,
    Upload,
    Video,
    ClearCache,
    ClearWeb,
    Shrink,
}

internal data class OtherActions(
    val boolean: (OtherSwitch, Boolean) -> Unit,
    val edit: (OtherEditor) -> Unit,
    val text: (String, Int, Int) -> Unit,
    val confirm: () -> Unit,
    val dismiss: () -> Unit,
    val retry: () -> Unit,
    val retryMutation: () -> Unit,
    val pickTree: () -> Unit,
    val destination: (OtherDestination) -> Unit,
)

private data class OtherRow(
    val key: String,
    val title: String,
    val summary: String = "",
    val category: String = "",
    val boolean: OtherSwitch? = null,
    val editor: OtherEditor? = null,
    val destination: OtherDestination? = null,
    val bookTree: Boolean = false,
    val heading: Boolean = false,
)

@Composable
private fun otherRows(settings: OtherSettingsSnapshot): List<OtherRow> {
    val languageNames = stringArrayResource(R.array.language)
    val languageValues = stringArrayResource(R.array.language_value)
    val homeNames = stringArrayResource(R.array.default_home_page)
    val homeValues = stringArrayResource(R.array.default_home_page_value)
    val rows = mutableListOf<OtherRow>()
    rows +=
        OtherRow(
            "language",
            stringResource(R.string.language),
            languageNames
                .getOrNull(languageValues.indexOf(settings.choices.getValue(OtherChoice.Language)))
                .orEmpty(),
            "",
            editor = OtherEditor.Language,
        )
    rows +=
        OtherRow(
            "category-1",
            stringResource(R.string.main_activity),
            category = stringResource(R.string.main_activity),
            heading = true,
        )
    rows +=
        OtherRow(
            "auto_refresh",
            stringResource(R.string.pt_auto_refresh),
            stringResource(R.string.ps_auto_refresh),
            stringResource(R.string.main_activity),
            boolean = OtherSwitch.AutoRefresh,
        )
    rows +=
        OtherRow(
            "onlyUpdateRead",
            stringResource(R.string.only_update_read),
            stringResource(R.string.ps_only_update_read),
            stringResource(R.string.main_activity),
            boolean = OtherSwitch.OnlyRead,
        )
    rows +=
        OtherRow(
            "defaultToRead",
            stringResource(R.string.pt_default_read),
            stringResource(R.string.ps_default_read),
            stringResource(R.string.main_activity),
            boolean = OtherSwitch.DefaultRead,
        )
    rows +=
        OtherRow(
            "showDiscovery",
            stringResource(R.string.show_discovery),
            "",
            stringResource(R.string.main_activity),
            boolean = OtherSwitch.Discovery,
        )
    rows +=
        OtherRow(
            "showDiscoveryFastScroller",
            stringResource(R.string.show_discovery_fast_scroller),
            "",
            stringResource(R.string.main_activity),
            boolean = OtherSwitch.DiscoveryScroller,
        )
    rows +=
        OtherRow(
            "showRss",
            stringResource(R.string.show_rss),
            "",
            stringResource(R.string.main_activity),
            boolean = OtherSwitch.Rss,
        )
    rows +=
        OtherRow(
            "defaultHomePage",
            stringResource(R.string.default_home_page),
            homeNames
                .getOrNull(homeValues.indexOf(settings.choices.getValue(OtherChoice.Home)))
                .orEmpty(),
            stringResource(R.string.main_activity),
            editor = OtherEditor.Home,
        )
    rows +=
        OtherRow(
            "category-9",
            stringResource(R.string.other_setting),
            category = stringResource(R.string.other_setting),
            heading = true,
        )
    rows +=
        OtherRow(
            "userAgent",
            stringResource(R.string.user_agent),
            settings.texts.getValue(OtherText.UserAgent),
            stringResource(R.string.other_setting),
            editor = OtherEditor.UserAgent,
        )
    rows +=
        OtherRow(
            "customHosts",
            stringResource(R.string.custom_hosts),
            stringResource(R.string.custom_hosts_summary),
            stringResource(R.string.other_setting),
            editor = OtherEditor.Hosts,
        )
    rows +=
        OtherRow(
            "webServiceWakeLock",
            stringResource(R.string.web_service_wake_lock),
            stringResource(R.string.web_service_wake_lock_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.WebWake,
        )
    rows +=
        OtherRow(
            "defaultBookTreeUri",
            stringResource(R.string.book_tree_uri_t),
            settings.texts.getValue(OtherText.BookTree).ifEmpty {
                stringResource(R.string.book_tree_uri_s)
            },
            stringResource(R.string.other_setting),
            bookTree = true,
        )
    rows +=
        OtherRow(
            "sourceEditMaxLine",
            stringResource(R.string.source_edit_text_max_line),
            stringResource(
                R.string.source_edit_max_line_summary,
                settings.numbers.getValue(OtherNumber.SourceLines).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.SourceLines,
        )
    rows +=
        OtherRow(
            "checkSource",
            stringResource(R.string.check_source_config),
            settings.checkSourceSummary,
            stringResource(R.string.other_setting),
            destination = OtherDestination.CheckSource,
        )
    rows +=
        OtherRow(
            "uploadRule",
            stringResource(R.string.direct_link_upload_rule),
            stringResource(R.string.direct_link_upload_rule_summary),
            stringResource(R.string.other_setting),
            destination = OtherDestination.Upload,
        )
    rows +=
        OtherRow(
            "Cronet",
            "Cronet",
            stringResource(R.string.pref_cronet_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.Cronet,
        )
    rows +=
        OtherRow(
            "antiAlias",
            stringResource(R.string.anti_alias),
            stringResource(R.string.pref_anti_alias_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.AntiAlias,
        )
    rows +=
        OtherRow(
            "bitmapCacheSize",
            stringResource(R.string.bitmap_cache_size),
            stringResource(
                R.string.bitmap_cache_size_summary,
                settings.numbers.getValue(OtherNumber.BitmapCache).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.BitmapCache,
        )
    rows +=
        OtherRow(
            "imageRetainNum",
            stringResource(R.string.image_retain_number),
            stringResource(
                R.string.image_retain_number_summary,
                settings.numbers.getValue(OtherNumber.ImageRetain).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.ImageRetain,
        )
    rows +=
        OtherRow(
            "preDownloadNum",
            stringResource(R.string.pre_download),
            stringResource(
                R.string.pre_download_s,
                settings.numbers.getValue(OtherNumber.PreDownload).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.PreDownload,
        )
    rows +=
        OtherRow(
            "replaceEnableDefault",
            stringResource(R.string.replace_enable_default_t),
            stringResource(R.string.replace_enable_default_s),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.ReplaceDefault,
        )
    rows +=
        OtherRow(
            "mediaButtonOnExit",
            stringResource(R.string.media_button_on_exit_title),
            stringResource(R.string.media_button_on_exit_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.MediaExit,
        )
    rows +=
        OtherRow(
            "readAloudByMediaButton",
            stringResource(R.string.read_aloud_by_media_button_title),
            stringResource(R.string.read_aloud_by_media_button_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.MediaRead,
        )
    rows +=
        OtherRow(
            "ignoreAudioFocus",
            stringResource(R.string.ignore_audio_focus_title),
            stringResource(R.string.ignore_audio_focus_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.IgnoreFocus,
        )
    rows +=
        OtherRow(
            "autoClearExpired",
            stringResource(R.string.auto_clear_expired),
            stringResource(R.string.auto_clear_expired_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.AutoClear,
        )
    rows +=
        OtherRow(
            "showAddToShelfAlert",
            stringResource(R.string.show_add_to_shelf_alert_title),
            stringResource(R.string.show_add_to_shelf_alert_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.AddAlert,
        )
    rows +=
        OtherRow(
            "autoUpdateVariant",
            stringResource(R.string.auto_update),
            stringResource(R.string.auto_update_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.AutoUpdate,
        )
    rows +=
        OtherRow(
            "liveUpdateNotifications",
            stringResource(R.string.live_update_notifications),
            stringResource(R.string.live_update_notifications_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.LiveNotifications,
        )
    rows +=
        OtherRow(
            "showMangaUi",
            stringResource(R.string.show_manga_ui),
            "",
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.Manga,
        )
    rows +=
        OtherRow(
            "videoSetting",
            stringResource(R.string.video_setting),
            stringResource(R.string.video_setting_summary),
            stringResource(R.string.other_setting),
            destination = OtherDestination.Video,
        )
    rows +=
        OtherRow(
            "webPort",
            stringResource(R.string.web_port_title),
            stringResource(
                R.string.web_port_summary,
                settings.numbers.getValue(OtherNumber.WebPort).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.WebPort,
        )
    rows +=
        OtherRow(
            "mcpPort",
            stringResource(R.string.mcp_port_title),
            stringResource(
                R.string.mcp_port_summary,
                settings.numbers.getValue(OtherNumber.McpPort).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.McpPort,
        )
    rows +=
        OtherRow(
            "jsSourceApiTokenRequired",
            stringResource(R.string.js_source_api_token_required_title),
            stringResource(R.string.js_source_api_token_required_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.TokenRequired,
        )
    rows +=
        OtherRow(
            "jsSourceApiToken",
            stringResource(R.string.js_source_api_token_title),
            stringResource(
                if (settings.tokenConfigured) R.string.js_source_api_token_configured
                else R.string.js_source_api_token_summary
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.Token,
        )
    rows +=
        OtherRow(
            "cleanCache",
            stringResource(R.string.clear_cache),
            stringResource(R.string.clear_cache_summary),
            stringResource(R.string.other_setting),
            destination = OtherDestination.ClearCache,
        )
    rows +=
        OtherRow(
            "clearWebViewData",
            stringResource(R.string.clear_webview_data),
            stringResource(R.string.clear_webview_data_summary),
            stringResource(R.string.other_setting),
            destination = OtherDestination.ClearWeb,
        )
    rows +=
        OtherRow(
            "shrinkDatabase",
            stringResource(R.string.shrink_database),
            stringResource(R.string.shrink_database_summary),
            stringResource(R.string.other_setting),
            destination = OtherDestination.Shrink,
        )
    rows +=
        OtherRow(
            "threadCount",
            stringResource(R.string.threads_num_title),
            stringResource(
                R.string.threads_num,
                settings.numbers.getValue(OtherNumber.Threads).toString(),
            ),
            stringResource(R.string.other_setting),
            editor = OtherEditor.Threads,
        )
    rows +=
        OtherRow(
            "process_text",
            stringResource(R.string.add_to_text_context_menu_t),
            stringResource(R.string.add_to_text_context_menu_s),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.ProcessText,
        )
    rows +=
        OtherRow(
            "recordLog",
            stringResource(R.string.record_log),
            stringResource(R.string.record_debug_log),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.Log,
        )
    rows +=
        OtherRow(
            "recordHttpLog",
            stringResource(R.string.record_http_log),
            stringResource(R.string.record_http_log_summary),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.HttpLog,
        )
    rows +=
        OtherRow(
            "recordHeapDump",
            stringResource(R.string.record_heap_dump_t),
            stringResource(R.string.record_heap_dump_s),
            stringResource(R.string.other_setting),
            boolean = OtherSwitch.HeapDump,
        )
    return rows
}

@Composable
internal fun OtherSettingsScreen(
    state: OtherSettingsState,
    actions: OtherActions,
    search: String? = null,
    searchFinished: () -> Unit = {},
    searchEmpty: () -> Unit = {},
) {
    val settings = state.settings ?: OtherSettingsSnapshot()
    val rows = otherRows(settings).filter { it.boolean?.let(settings::visible) ?: true }
    val enabled =
        !state.loading &&
            !state.failed &&
            !state.busy &&
            !state.writeFailed &&
            !state.pendingCommit &&
            !state.interrupted
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRetry by rememberSaveable { mutableStateOf(false) }
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
        LazyColumn(
            state = scroll,
            modifier = Modifier.fillMaxSize().testTag("other-settings-list"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (state.loading || state.busy)
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.error != null || state.interrupted)
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            state.error ?: "上次保存已中断，请确认是否继续",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("other-settings-error"),
                        )
                        TextButton(
                            onClick = {
                                if (state.interrupted && !state.pendingCommit) confirmRetry = true
                                else actions.retry()
                            },
                            enabled = !state.loading && !state.busy,
                            modifier =
                                Modifier.heightIn(min = 48.dp).testTag("other-settings-retry"),
                        ) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            items(rows, key = { it.key }) { row ->
                val base =
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("other-row-${row.key}")
                val input =
                    when {
                        row.boolean != null ->
                            base.toggleable(
                                settings.switches.getValue(row.boolean),
                                enabled,
                                Role.Switch,
                            ) {
                                actions.boolean(row.boolean, it)
                            }
                        row.heading -> base
                        else ->
                            base.clickable(enabled = enabled) {
                                when {
                                    row.editor != null -> actions.edit(row.editor)
                                    row.bookTree -> actions.pickTree()
                                    row.destination != null ->
                                        if (
                                            row.destination in
                                                listOf(
                                                    OtherDestination.ClearCache,
                                                    OtherDestination.ClearWeb,
                                                    OtherDestination.Shrink,
                                                )
                                        )
                                            confirmation = row.destination.name
                                        else actions.destination(row.destination)
                                    else -> Unit
                                }
                            }
                    }
                Row(
                    input.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.title,
                            color =
                                if (row.heading) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            style =
                                if (row.heading) MaterialTheme.typography.titleSmall
                                else MaterialTheme.typography.bodyLarge,
                        )
                        if (row.summary.isNotEmpty())
                            Text(row.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    row.boolean?.let {
                        Switch(settings.switches.getValue(it), null, enabled = enabled)
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
                                listOf(row.category, row.title)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" > "),
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("other-search-${row.key}")
                                    .clickable {
                                        searchFinished()
                                        scope.launch {
                                            scroll.animateScrollToItem(
                                                rows.indexOf(row) +
                                                    (if (state.loading || state.busy) 1 else 0) +
                                                    (if (state.error != null || state.interrupted) 1
                                                    else 0)
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
        state.draft?.editor?.let { editor ->
            OtherEditorDialog(editor, state, actions) { confirmRetry = true }
        }
        confirmation?.let { value ->
            val action = OtherDestination.valueOf(value)
            AlertDialog(
                onDismissRequest = { confirmation = null },
                title = {
                    Text(
                        stringResource(
                            when (action) {
                                OtherDestination.ClearCache -> R.string.clear_cache
                                OtherDestination.ClearWeb -> R.string.clear_webview_data
                                else -> R.string.sure
                            }
                        )
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (action == OtherDestination.Shrink) R.string.shrink_database
                            else R.string.sure_del
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        {
                            confirmation = null
                            actions.destination(action)
                        },
                        enabled = enabled,
                        modifier =
                            Modifier.heightIn(min = 48.dp).testTag("other-maintenance-confirm"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton({ confirmation = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        if (confirmRetry)
            AlertDialog(
                onDismissRequest = { confirmRetry = false },
                title = { Text(stringResource(R.string.retry)) },
                text = { Text("上次保存已中断，是否继续应用该设置？") },
                confirmButton = {
                    TextButton(
                        {
                            confirmRetry = false
                            actions.retryMutation()
                        },
                        enabled = !state.busy && !state.failed && !state.loading,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("other-retry-confirm"),
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        { confirmRetry = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
    }
}

@Composable
private fun OtherEditorDialog(
    editor: OtherEditor,
    state: OtherSettingsState,
    actions: OtherActions,
    retryMutation: () -> Unit,
) {
    val draft = state.draft ?: return
    val row = otherRows(state.settings ?: OtherSettingsSnapshot()).first { it.editor == editor }
    val enabled =
        !state.loading &&
            !state.failed &&
            !state.busy &&
            !state.pendingCommit &&
            !state.writeFailed &&
            !state.interrupted
    val number = OtherNumber.entries.find { it.name == editor.name }
    val choice = OtherChoice.entries.find { it.name == editor.name }
    val text =
        TextFieldValue(
            draft.text,
            TextRange(
                draft.selectionStart.coerceIn(0, draft.text.length),
                draft.selectionEnd.coerceIn(0, draft.text.length),
            ),
        )
    val colors =
        CodeSyntaxColors(
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_blue_800),
            colorResource(R.color.md_blue_800),
            colorResource(R.color.md_orange_900),
            colorResource(R.color.md_orange_900),
        )
    var syntax by remember(editor) { mutableStateOf(AnnotatedString(draft.text)) }
    LaunchedEffect(editor, draft.text, colors) {
        syntax =
            if (editor == OtherEditor.Hosts)
                withContext(Dispatchers.Default) { projectCodeSyntax(draft.text, colors) }
            else AnnotatedString(draft.text)
    }
    val transformation =
        if (editor == OtherEditor.Token) PasswordVisualTransformation()
        else if (editor == OtherEditor.Hosts)
            VisualTransformation { original ->
                TransformedText(
                    if (syntax.text == original.text) syntax else original,
                    OffsetMapping.Identity,
                )
            }
        else VisualTransformation.None
    AlertDialog(
        onDismissRequest = { if (enabled) actions.dismiss() },
        title = { Text(row.title) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.pendingCommit || state.writeFailed || state.failed || state.interrupted)
                    TextButton(
                        onClick = {
                            if (state.interrupted && !state.pendingCommit) retryMutation()
                            else actions.retry()
                        },
                        enabled = !state.loading && !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("other-editor-retry"),
                    ) {
                        Text(stringResource(R.string.retry))
                    }
                if (choice != null) {
                    val names =
                        stringArrayResource(
                            if (choice == OtherChoice.Language) R.array.language
                            else R.array.default_home_page
                        )
                    val values =
                        stringArrayResource(
                            if (choice == OtherChoice.Language) R.array.language_value
                            else R.array.default_home_page_value
                        )
                    values.forEachIndexed { index, value ->
                        Row(
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("other-choice-$value")
                                .clickable(enabled = enabled) {
                                    actions.text(value, 0, 0)
                                    actions.confirm()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(draft.text == value, null, enabled = enabled)
                            Text(names[index])
                        }
                    }
                } else {
                    OutlinedTextField(
                        text,
                        { actions.text(it.text, it.selection.start, it.selection.end) },
                        enabled = enabled,
                        singleLine = editor != OtherEditor.Hosts,
                        minLines = if (editor == OtherEditor.Hosts) 4 else 1,
                        visualTransformation = transformation,
                        isError = state.invalidNumber,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    when {
                                        number != null -> KeyboardType.Number
                                        editor == OtherEditor.Token -> KeyboardType.Password
                                        else -> KeyboardType.Text
                                    }
                            ),
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontFamily =
                                    if (editor == OtherEditor.Hosts) FontFamily.Monospace
                                    else FontFamily.Default
                            ),
                        label = {
                            Text(
                                if (editor == OtherEditor.Hosts)
                                    stringResource(R.string.json_format)
                                else row.title
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("other-editor-text"),
                    )
                    number?.let { key ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = {
                                    val value =
                                        ((draft.text.toLongOrNull() ?: key.default.toLong())
                                                .coerceIn(
                                                    key.minimum.toLong(),
                                                    key.maximum.toLong(),
                                                ) - 1)
                                            .coerceIn(key.minimum.toLong(), key.maximum.toLong())
                                            .toString()
                                    actions.text(value, value.length, value.length)
                                },
                                enabled = enabled,
                                modifier =
                                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("other-number-minus"),
                            ) {
                                Text("−")
                            }
                            Text(
                                "${key.minimum} – ${key.maximum}",
                                Modifier.align(Alignment.CenterVertically),
                            )
                            TextButton(
                                onClick = {
                                    val value =
                                        ((draft.text.toLongOrNull() ?: key.default.toLong())
                                                .coerceIn(
                                                    key.minimum.toLong(),
                                                    key.maximum.toLong(),
                                                ) + 1)
                                            .coerceIn(key.minimum.toLong(), key.maximum.toLong())
                                            .toString()
                                    actions.text(value, value.length, value.length)
                                },
                                enabled = enabled,
                                modifier =
                                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                        .testTag("other-number-plus"),
                            ) {
                                Text("+")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (choice == null)
                TextButton(
                    actions.confirm,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("other-editor-ok"),
                ) {
                    Text(stringResource(R.string.ok))
                }
        },
        dismissButton = {
            TextButton(
                actions.dismiss,
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("other-editor-cancel"),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
