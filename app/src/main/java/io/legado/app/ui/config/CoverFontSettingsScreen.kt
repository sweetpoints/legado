package io.legado.app.ui.config

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.cover.*
import io.legado.app.data.repository.CoverRequest
import io.legado.app.ui.components.cover.ComposeCover
import kotlinx.coroutines.launch
import java.io.File

internal data class CoverFontSettingsActions(val boolean: (CoverFontSwitch, Boolean) -> Unit, val edit: (CoverFontSize) -> Unit,
    val number: (Int) -> Unit, val confirm: (Boolean) -> Unit, val font: () -> Unit, val dismiss: () -> Unit, val retry: () -> Unit)
private data class CoverFontRow(val key: String, val title: String, val summary: String = "", val boolean: CoverFontSwitch? = null,
    val size: CoverFontSize? = null)
@Composable private fun coverFontRows(settings: CoverFontSettingsSnapshot): List<CoverFontRow> {
    val rows = mutableListOf<CoverFontRow>()
    val switches = listOf(Triple(CoverFontSwitch.Horizontal, R.string.cover_horizontal, R.string.cover_horizontal_summary),
        Triple(CoverFontSwitch.Adaptive, R.string.cover_title_adaptive, R.string.cover_title_adaptive_summary),
        Triple(CoverFontSwitch.Punctuation, R.string.cover_keep_punctuation, R.string.cover_keep_punctuation_summary),
        Triple(CoverFontSwitch.CustomSize, R.string.cover_custom_font_size, R.string.cover_custom_font_size_summary))
    switches.take(3).forEach { (key, title, summary) -> rows += CoverFontRow(key.key, stringResource(title), stringResource(summary), boolean = key) }
    rows += CoverFontRow("coverFont", stringResource(R.string.cover_font_select), settings.fontPath.takeIf { it.isNotBlank() }?.let { File(it).name } ?: stringResource(R.string.default_font))
    val (custom, title, summary) = switches.last(); rows += CoverFontRow(custom.key, stringResource(title), stringResource(summary), boolean = custom)
    val titles = listOf(R.string.cover_title_large_size, R.string.cover_title_small_size, R.string.cover_author_large_size, R.string.cover_author_small_size)
    CoverFontSize.entries.forEachIndexed { index, key -> rows += CoverFontRow(key.key, stringResource(titles[index]), "${settings.sizes.getValue(key)}%", size = key) }
    return rows
}
@Composable internal fun CoverFontSettingsScreen(state: CoverFontSettingsState, actions: CoverFontSettingsActions,
    search: String? = null, searchFinished: () -> Unit = {}, searchEmpty: () -> Unit = {}) {
    val settings = state.settings ?: CoverFontSettingsSnapshot(); val rows = coverFontRows(settings)
    val enabled = !state.loading && !state.failed && !state.busy; val scroll = rememberLazyListState(); val scope = rememberCoroutineScope()
    val found = search?.let { query -> rows.filter { configPreferenceMatches(query, it.title, it.summary, emptyList()) } }.orEmpty()
    LaunchedEffect(search, state.loading, state.failed) { if (search != null && !state.loading && !state.failed && found.isEmpty()) searchEmpty() }
    Surface(color = MaterialTheme.colorScheme.surface) {
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().testTag("cover-font-settings-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Column(Modifier.padding(16.dp)) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("cover-font-error"))
                TextButton(onClick = actions.retry, enabled = !state.loading && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("cover-font-retry")) { Text(stringResource(R.string.retry)) }
            } } }
            items(rows, key = { it.key }) { row ->
                val canEdit = enabled && (row.size == null || settings.customSizesEnabled)
                val base = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cover-font-row-${row.key}")
                val input = if (row.boolean != null) base.toggleable(settings.switches.getValue(row.boolean), enabled = canEdit, role = Role.Switch) { actions.boolean(row.boolean, it) }
                    else base.clickable(enabled = canEdit) { if (row.size != null) actions.edit(row.size) else actions.font() }
                Row(input.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(row.title, style = MaterialTheme.typography.bodyLarge)
                        if (row.summary.isNotEmpty()) Text(row.summary, style = MaterialTheme.typography.bodySmall) }
                    row.boolean?.let { Switch(checked = settings.switches.getValue(it), onCheckedChange = null, enabled = canEdit) }
                }
            }
            item(key = "coverPreview") {
                Row(Modifier.fillMaxWidth().padding(16.dp).testTag("cover-font-row-coverPreview"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ComposeCover(CoverRequest(name = "开源阅读", author = "开源阅读LegadoTeam"), Modifier.weight(1f).testTag("cover-font-preview-short"), stringResource(R.string.cover_preview_short))
                    ComposeCover(CoverRequest(name = "开源阅读可以看小说、看漫画、听书", author = "开源阅读"), Modifier.weight(1f).testTag("cover-font-preview-long"), stringResource(R.string.cover_preview_long))
                }
            }
        }
        if (search != null && found.isNotEmpty()) AlertDialog(onDismissRequest = searchFinished, title = { Text(search) }, text = {
            LazyColumn { items(found, key = { it.key }) { row -> Text(row.title, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cover-font-search-${row.key}").clickable {
                searchFinished(); scope.launch { scroll.animateScrollToItem(rows.indexOf(row) + (if (state.loading || state.busy) 1 else 0) + (if (state.error != null) 1 else 0)) }
            }.padding(16.dp)) } }
        }, confirmButton = {})
        state.editing?.let { key ->
            var input by rememberSaveable(key, state.number) { mutableStateOf(state.number.toString()) }
            AlertDialog(onDismissRequest = actions.dismiss, title = { Text(rows.first { it.size == key }.title) },
                text = { OutlinedTextField(input, { value ->
                    if (value.isEmpty()) input = value
                    else if (value.all(Char::isDigit)) value.toIntOrNull()?.let { number -> if (number < 50) input = value else { val clamped = number.coerceAtMost(200); input = clamped.toString(); actions.number(clamped) } }
                }, enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), suffix = { Text("%") }, modifier = Modifier.testTag("cover-font-number")) },
                confirmButton = { TextButton(onClick = { actions.confirm(false) }, enabled = enabled && input.toIntOrNull()?.let { it in 50..200 } == true, modifier = Modifier.heightIn(min = 48.dp).testTag("cover-font-confirm")) { Text(stringResource(R.string.ok)) } },
                dismissButton = { TextButton(onClick = { actions.confirm(true) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("cover-font-default")) { Text(stringResource(R.string.btn_default_s)) } })
        }
    }
}
