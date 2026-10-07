package io.legado.app.ui.book.cache

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.data.repository.BookCachePreferences

class BookCacheActions(
    val back: () -> Unit = {},
    val group: (Long) -> Unit = {},
    val download: (Boolean) -> Unit = {},
    val confirm: () -> Unit = {},
    val cancelConfirm: () -> Unit = {},
    val toggle: (String) -> Unit = {},
    val export: (String?) -> Unit = {},
    val folder: () -> Unit = {},
    val preferences: ((BookCachePreferences) -> BookCachePreferences) -> Unit = {},
    val refreshPreferences: () -> Unit = {},
    val section: (Boolean?, String?, String?, String?) -> Unit = { _, _, _, _ -> },
    val confirmSection: () -> Unit = {},
    val cancelSection: () -> Unit = {},
    val preview: () -> Unit = {},
    val rememberName: () -> Unit = {},
    val openSettings: (String) -> Unit = {},
    val settings: (String) -> Unit = {},
    val saveSettings: () -> Unit = {},
    val cancelSettings: () -> Unit = {},
    val log: () -> Unit = {},
    val retry: () -> Unit = {},
    val cancelTransfer: () -> Unit = {},
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCacheScreen(state: BookCacheState, actions: BookCacheActions) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var groups by rememberSaveable { mutableStateOf(false) }
    var downloads by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = !state.busy && !state.loading
    val message =
        state.error
            ?: when (state.issue) {
                BookCacheIssue.NoBook -> stringResource(R.string.no_book)
                BookCacheIssue.SettingsNotSaved ->
                    stringResource(R.string.book_cache_settings_not_saved)
                BookCacheIssue.ExportInterrupted ->
                    stringResource(R.string.book_cache_export_interrupted)
                null -> null
            }
    fun edit(kind: String) {
        menu = false
        actions.openSettings(kind)
    }
    Surface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(actions.back, Modifier.testTag("book-cache-back")) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.back),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.offline_cache),
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            state.groups.firstOrNull { it.id == state.group }?.name
                                ?: stringResource(R.string.no_group),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Box {
                        val title =
                            stringResource(
                                if (state.running) R.string.stop else R.string.download_start
                            )
                        Box(
                            Modifier.size(48.dp)
                                .combinedClickable(
                                    enabled = enabled,
                                    onClickLabel = title,
                                    onClick = { actions.download(true) },
                                    onLongClick = { downloads = true },
                                )
                                .testTag("book-cache-download"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(
                                    if (state.running) R.drawable.ic_stop_black_24dp
                                    else R.drawable.ic_play_24dp
                                ),
                                title,
                            )
                        }
                        DropdownMenu(downloads, { downloads = false }) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.menu_download_after)) },
                                {
                                    downloads = false
                                    actions.download(true)
                                },
                                Modifier.testTag("book-cache-download-after"),
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.menu_download_all)) },
                                {
                                    downloads = false
                                    actions.download(false)
                                },
                                Modifier.testTag("book-cache-download-all"),
                            )
                        }
                    }
                    Box {
                        IconButton({ groups = true }, Modifier.testTag("book-cache-groups")) {
                            Icon(
                                painterResource(R.drawable.ic_groups),
                                stringResource(R.string.group),
                            )
                        }
                        DropdownMenu(groups, { groups = false }) {
                            state.groups.forEach { group ->
                                DropdownMenuItem(
                                    { Text(group.name) },
                                    {
                                        groups = false
                                        actions.group(group.id)
                                    },
                                    Modifier.testTag("book-cache-group-${group.id}"),
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(
                            {
                                actions.refreshPreferences()
                                menu = true
                            },
                            Modifier.testTag("book-cache-menu"),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_more_vert),
                                stringResource(R.string.more_menu),
                            )
                        }
                        DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 440.dp)) {
                            DropdownMenuItem(
                                { Text(stringResource(R.string.export_all)) },
                                {
                                    menu = false
                                    actions.export(null)
                                },
                                enabled = enabled,
                                modifier = Modifier.testTag("book-cache-export-all"),
                            )
                            CachePreferenceMenu(
                                R.string.replace_purify,
                                state.preferences.replace,
                                "replace",
                            ) {
                                actions.preferences { it.copy(replace = !it.replace) }
                            }
                            CachePreferenceMenu(
                                R.string.custom_export_section,
                                state.preferences.custom,
                                "custom",
                            ) {
                                actions.preferences { it.copy(custom = !it.custom) }
                            }
                            CachePreferenceMenu(
                                R.string.export_to_web_dav,
                                state.preferences.webDav,
                                "webdav",
                            ) {
                                actions.preferences { it.copy(webDav = !it.webDav) }
                            }
                            CachePreferenceMenu(
                                R.string.export_no_chapter_name,
                                state.preferences.noChapterName,
                                "chapter-name",
                            ) {
                                actions.preferences { it.copy(noChapterName = !it.noChapterName) }
                            }
                            CachePreferenceMenu(
                                R.string.export_pics_file,
                                state.preferences.pictures,
                                "pictures",
                            ) {
                                actions.preferences { it.copy(pictures = !it.pictures) }
                            }
                            CachePreferenceMenu(
                                R.string.parallel_export_book,
                                state.preferences.parallel,
                                "parallel",
                            ) {
                                actions.preferences { it.copy(parallel = !it.parallel) }
                            }
                            DropdownMenuItem(
                                { Text(stringResource(R.string.export_folder)) },
                                {
                                    menu = false
                                    actions.folder()
                                },
                                enabled = enabled,
                                modifier = Modifier.testTag("book-cache-export-folder"),
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.export_file_name)) },
                                { edit("name") },
                                modifier = Modifier.testTag("book-cache-file-name"),
                            )
                            DropdownMenuItem(
                                {
                                    Text(
                                        "${stringResource(R.string.export_type)}(${state.preferences.exportType})"
                                    )
                                },
                                {
                                    menu = false
                                    settings = "type"
                                },
                                modifier = Modifier.testTag("book-cache-type"),
                            )
                            DropdownMenuItem(
                                {
                                    Text(
                                        "${stringResource(R.string.export_charset)}(${state.preferences.charset})"
                                    )
                                },
                                { edit("charset") },
                                modifier = Modifier.testTag("book-cache-charset"),
                            )
                            DropdownMenuItem(
                                { Text(stringResource(R.string.log)) },
                                {
                                    menu = false
                                    actions.log()
                                },
                                modifier = Modifier.testTag("book-cache-log"),
                            )
                        }
                    }
                }
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { error ->
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(actions.retry, enabled = !state.busy) {
                        Text(stringResource(R.string.retry))
                    }
                    if (state.folder != null)
                        TextButton(actions.cancelTransfer) { Text(stringResource(R.string.cancel)) }
                }
            }
            LazyColumn(Modifier.fillMaxSize().testTag("book-cache-list")) {
                items(state.rows, key = { it.book.key }) { row ->
                    Column(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("book-cache-row-${row.book.key}")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.book.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.author_show, row.book.author),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    when {
                                        row.book.local -> stringResource(R.string.local_book)
                                        row.cached == null -> stringResource(R.string.loading)
                                        else ->
                                            stringResource(
                                                R.string.download_count,
                                                row.cached.size,
                                                row.total,
                                            )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!row.book.local)
                                IconButton(
                                    { actions.toggle(row.book.key) },
                                    Modifier.testTag("book-cache-toggle-${row.book.key}"),
                                    enabled = !state.busy,
                                ) {
                                    Icon(
                                        painterResource(
                                            if (row.downloading) R.drawable.ic_stop_black_24dp
                                            else R.drawable.ic_play_24dp
                                        ),
                                        stringResource(
                                            if (row.downloading) R.string.stop else R.string.start
                                        ),
                                    )
                                }
                            TextButton(
                                { actions.export(row.book.key) },
                                Modifier.testTag("book-cache-export-${row.book.key}"),
                                enabled = !state.busy,
                            ) {
                                Text(stringResource(R.string.export))
                            }
                        }
                        if (row.message != null)
                            Text(
                                row.message,
                                Modifier.testTag("book-cache-message-${row.book.key}"),
                            )
                        else if (row.progress != null)
                            LinearProgressIndicator(
                                progress = {
                                    if (row.total > 0)
                                        (row.progress.toFloat() / row.total).coerceIn(0f, 1f)
                                    else 0f
                                },
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .testTag("book-cache-progress-${row.book.key}"),
                            )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (state.confirmAfterCurrent != null)
        AlertDialog(
            onDismissRequest = actions.cancelConfirm,
            title = { Text(stringResource(R.string.draw)) },
            text = { Text(stringResource(R.string.sure_cache_book)) },
            confirmButton = {
                TextButton(actions.confirm, Modifier.testTag("book-cache-confirm-download")) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(actions.cancelConfirm) { Text(stringResource(R.string.no)) }
            },
        )
    if (settings == "type")
        AlertDialog(
            onDismissRequest = { settings = null },
            title = { Text(stringResource(R.string.export_type)) },
            text = {
                Column {
                    listOf("txt", "epub", "pdf").forEachIndexed { index, type ->
                        TextButton(
                            {
                                actions.preferences { it.copy(type = index) }
                                settings = null
                            },
                            Modifier.fillMaxWidth().testTag("book-cache-type-$type"),
                        ) {
                            Text(type)
                        }
                    }
                }
            },
            confirmButton = {},
        )
    state.settings?.let { setting ->
        val kind = setting.kind
        AlertDialog(
            onDismissRequest = actions.cancelSettings,
            title = {
                Text(
                    stringResource(
                        if (kind == "name") R.string.export_file_name else R.string.set_charset
                    )
                )
            },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (kind == "name") Text("Variable: name, author.")
                    OutlinedTextField(
                        setting.draft,
                        actions.settings,
                        Modifier.fillMaxWidth().testTag("book-cache-setting-draft"),
                        label = { Text(if (kind == "name") "file name js" else "charset name") },
                    )
                    if (kind == "charset")
                        AppConst.charsets
                            .filter { it.contains(setting.draft, ignoreCase = true) }
                            .take(8)
                            .forEach { charset ->
                                TextButton({ actions.settings(charset) }) { Text(charset) }
                            }
                }
            },
            confirmButton = {
                TextButton(actions.saveSettings, Modifier.testTag("book-cache-setting-save")) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.cancelSettings, Modifier.testTag("book-cache-setting-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.section?.let { section ->
        AlertDialog(
            onDismissRequest = actions.cancelSection,
            title = { Text(stringResource(R.string.select_section_export)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                ) {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable {
                            actions.section(true, null, null, null)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(section.all, { actions.section(true, null, null, null) })
                        Text(stringResource(R.string.export_all))
                    }
                    Row(
                        Modifier.fillMaxWidth().combinedClickable {
                            actions.section(false, null, null, null)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(!section.all, { actions.section(false, null, null, null) })
                        Text(stringResource(R.string.custom_export))
                    }
                    OutlinedTextField(
                        section.name,
                        { actions.section(null, null, null, it) },
                        Modifier.fillMaxWidth().testTag("book-cache-episode-name").onFocusChanged {
                            if (!it.isFocused) actions.rememberName()
                        },
                        enabled = !section.all,
                        label = { Text(stringResource(R.string.export_file_name)) },
                        supportingText = {
                            Text(section.preview ?: "Variable: name, author, epubIndex")
                        },
                        trailingIcon = {
                            IconButton(
                                actions.preview,
                                enabled = !section.all,
                                modifier = Modifier.testTag("book-cache-episode-preview"),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_play_24dp),
                                    stringResource(R.string.start),
                                )
                            }
                        },
                    )
                    OutlinedTextField(
                        section.size,
                        { actions.section(null, it.filter(Char::isDigit).take(6), null, null) },
                        Modifier.fillMaxWidth().testTag("book-cache-episode-size"),
                        enabled = !section.all,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.file_contains_number)) },
                    )
                    OutlinedTextField(
                        section.scope,
                        { actions.section(null, null, it, null) },
                        Modifier.fillMaxWidth().testTag("book-cache-episode-scope"),
                        enabled = !section.all,
                        label = { Text(stringResource(R.string.export_chapter_index)) },
                        placeholder = { Text("1-5,8,10-18") },
                        isError = section.invalidScope,
                        supportingText = {
                            if (section.invalidScope)
                                Text(stringResource(R.string.error_scope_input))
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    actions.confirmSection,
                    Modifier.testTag("book-cache-confirm-section"),
                    enabled = !state.busy,
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(actions.cancelSection, enabled = !state.busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun CachePreferenceMenu(title: Int, checked: Boolean, tag: String, action: () -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(title)) },
        action,
        trailingIcon = { Checkbox(checked, null) },
        modifier = Modifier.testTag("book-cache-pref-$tag"),
    )
}
