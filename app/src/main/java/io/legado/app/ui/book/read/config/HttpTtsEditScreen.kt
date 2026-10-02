package io.legado.app.ui.book.read.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.HttpTtsEditorField
import io.legado.app.model.analyzeRule.*

internal fun httpTtsFieldLabel(field: HttpTtsEditorField): Int? = when (field) {
    HttpTtsEditorField.Name -> R.string.name; HttpTtsEditorField.Pause -> R.string.http_tts_pause_duration
    HttpTtsEditorField.Url, HttpTtsEditorField.ContentType, HttpTtsEditorField.JsLib -> null
    HttpTtsEditorField.ConcurrentRate -> R.string.concurrent_rate; HttpTtsEditorField.LoginUrl -> R.string.login_url
    HttpTtsEditorField.LoginUi -> R.string.login_ui; HttpTtsEditorField.LoginCheckJs -> R.string.login_check_js
    HttpTtsEditorField.Header -> R.string.source_http_header
}
internal fun httpTtsLiteralLabel(field: HttpTtsEditorField) = when (field) {
    HttpTtsEditorField.Url -> "url"; HttpTtsEditorField.ContentType -> "Content-Type"; HttpTtsEditorField.JsLib -> "jsLib"
    else -> field.name
}
private class HttpTtsHighlight(private val field: HttpTtsEditorField, private val orange: Color,
    private val blue: Color, private val grey: Color, private val lightBlue: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length !in 1..4096) return TransformedText(text, OffsetMapping.Identity)
        val patterns = when (field) {
            HttpTtsEditorField.Url, HttpTtsEditorField.LoginUrl, HttpTtsEditorField.Header, HttpTtsEditorField.JsLib ->
                listOf(legadoPattern to orange, jsonPattern to blue, wrapPattern to grey, operationPattern to orange, jsPattern to lightBlue)
            HttpTtsEditorField.LoginUi -> listOf(jsonPattern to blue)
            HttpTtsEditorField.LoginCheckJs -> listOf(wrapPattern to grey, operationPattern to orange, jsPattern to lightBlue)
            else -> emptyList()
        }
        val result = AnnotatedString.Builder(text)
        patterns.forEach { (pattern, color) -> val matcher = pattern.matcher(text.text)
            while (matcher.find()) result.addStyle(SpanStyle(color = color), matcher.start(), matcher.end()) }
        return TransformedText(result.toAnnotatedString(), OffsetMapping.Identity)
    }
}
@Composable
internal fun HttpTtsEditScreen(state: HttpTtsEditUiState,
    onEdit: (HttpTtsEditorField, String, Int, Int) -> Unit, onFocus: (HttpTtsEditorField) -> Unit,
    onCookie: (Boolean) -> Unit, onAction: (HttpTtsEditorAction) -> Unit,
    onHeader: () -> Unit, onDeleteHeader: () -> Unit, onCloseHeader: () -> Unit,
    onExit: () -> Unit, onKeepEditing: () -> Unit, onDiscard: () -> Unit, modifier: Modifier = Modifier, onRetry: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val requests = remember { HttpTtsEditorField.entries.associateWith { FocusRequester() } }
    LaunchedEffect(state.focus) { state.focus?.let { requests[it]?.requestFocus() } }
    BackHandler(onBack = onExit)
    val orange = colorResource(R.color.md_orange_900); val blue = colorResource(R.color.md_blue_800)
    val grey = colorResource(R.color.md_blue_grey_500); val lightBlue = colorResource(R.color.md_light_blue_600)
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onExit, Modifier.testTag("http-tts-back")) { Text(stringResource(R.string.cancel)) }
                Text(stringResource(R.string.speak_engine), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton({ onAction(HttpTtsEditorAction.Code) }, enabled = !state.busy && !state.loading && !state.loadFailed) { Text(stringResource(R.string.edit_content)) }
                TextButton({ onAction(HttpTtsEditorAction.Saved) }, enabled = !state.busy && !state.loading && !state.loadFailed && !state.finished,
                    modifier = Modifier.testTag("http-tts-save")) { Text(stringResource(R.string.action_save)) }
                Box {
                    TextButton({ menu = true }, Modifier.testTag("http-tts-menu")) { Text("⋮") }
                    DropdownMenu(menu, { menu = false }) {
                        listOf(R.string.login to { onAction(HttpTtsEditorAction.Login) }, R.string.show_login_header to onHeader,
                            R.string.del_login_header to onDeleteHeader, R.string.copy_source to { onAction(HttpTtsEditorAction.Copy) },
                            R.string.paste_source to { onAction(HttpTtsEditorAction.Paste) }, R.string.log to { onAction(HttpTtsEditorAction.Log) },
                            R.string.help to { onAction(HttpTtsEditorAction.Help) }).forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = !state.busy && !state.loading && !state.loadFailed,
                                onClick = { menu = false; action() })
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                if (state.loadFailed) TextButton(onRetry, Modifier.testTag("http-tts-retry")) { Text(stringResource(R.string.retry)) }
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                HttpTtsEditorField.entries.forEach { field ->
                    val text = state.draft.text(field)
                    val selection = state.selections[field] ?: HttpTtsEditorSelection()
                    val range = TextRange(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))
                    var value by rememberSaveable(field, state.draft.id, stateSaver = TextFieldValue.Saver) {
                        mutableStateOf(TextFieldValue(text, range))
                    }
                    // Keep the IME composing range during local edits; replace only external data/selection.
                    LaunchedEffect(text, range) { if (value.text != text || value.selection != range) value = TextFieldValue(text, range) }
                    val transformation = remember(field, orange, blue, grey, lightBlue) { HttpTtsHighlight(field, orange, blue, grey, lightBlue) }
                    OutlinedTextField(value, { value = it; onEdit(field, it.text, it.selection.start, it.selection.end) },
                        label = { Text(httpTtsFieldLabel(field)?.let { stringResource(it) } ?: httpTtsLiteralLabel(field)) },
                        enabled = !state.busy && !state.loading && !state.loadFailed && !state.finished,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field == HttpTtsEditorField.Pause) KeyboardType.Number else KeyboardType.Text),
                        visualTransformation = transformation,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("http-tts-field:${field.name}")
                            .focusRequester(requests.getValue(field)).onFocusChanged { if (it.isFocused) onFocus(field) })
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.draft.cookie, onCookie, enabled = !state.busy && !state.loading && !state.loadFailed && !state.finished,
                        modifier = Modifier.testTag("http-tts-cookie"))
                    Text(stringResource(R.string.auto_save_cookie))
                }
            }
        }
    }
    if (state.exit) AlertDialog(onDismissRequest = onKeepEditing, title = { Text(stringResource(R.string.exit)) },
        text = { Text(stringResource(R.string.exit_no_save)) },
        confirmButton = { TextButton(onKeepEditing) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(onDiscard) { Text(stringResource(R.string.no)) } })
    state.header?.let { header -> AlertDialog(onDismissRequest = onCloseHeader, title = { Text(stringResource(R.string.login_header)) },
        text = { Text(header.ifBlank { stringResource(R.string.empty) }) },
        confirmButton = { TextButton(onCloseHeader) { Text(stringResource(R.string.ok)) } }) }
}
