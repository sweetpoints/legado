package io.legado.app.ui.code

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.CurlDirection

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CurlConversionScreen(state: CurlConversionState, canInsert: Boolean,
    onInput: (String, Int, Int) -> Unit, onDirection: (CurlDirection) -> Unit, onConvert: () -> Unit,
    onCopy: () -> Unit, onInsert: () -> Unit, onClose: () -> Unit, onRetry: () -> Unit,
    modifier: Modifier = Modifier) {
    val editable = !state.loading && !state.loadFailed && !state.finished
    val hasOutput = state.draft.output.isNotEmpty() && editable
    var editor by remember { mutableStateOf(TextFieldValue(state.draft.input, TextRange(state.draft.selectionStart, state.draft.selectionEnd))) }
    LaunchedEffect(state.draft.input, state.draft.selectionStart, state.draft.selectionEnd) {
        val selection = TextRange(state.draft.selectionStart, state.draft.selectionEnd)
        if (editor.text != state.draft.input || editor.selection != selection) editor = TextFieldValue(state.draft.input, selection)
    }
    val curlFirst = state.draft.direction == CurlDirection.CurlToAnalyze
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(title = { Text(stringResource(R.string.curl_analyze_url_converter), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, navigationIcon = {
                IconButton(onClose, Modifier.testTag("curl-converter-close")) { Icon(painterResource(R.drawable.ic_baseline_close), stringResource(R.string.close)) }
            }, actions = {
                IconButton(onConvert, enabled = editable && !state.converting, modifier = Modifier.testTag("curl-converter-convert")) {
                    Icon(painterResource(R.drawable.ic_swap_horiz), stringResource(R.string.curl_converter_convert)) }
                IconButton(onCopy, enabled = hasOutput, modifier = Modifier.testTag("curl-converter-copy")) {
                    Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.copy_text)) }
                if (canInsert) IconButton(onInsert, enabled = hasOutput && !state.inserting, modifier = Modifier.testTag("curl-converter-insert")) {
                    Icon(painterResource(R.drawable.ic_check), stringResource(R.string.curl_converter_insert)) }
            })
            Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp)) {
                CurlDirection.entries.forEach { direction ->
                    Text(stringResource(if (direction == CurlDirection.CurlToAnalyze) R.string.curl_to_analyze_url else R.string.analyze_url_to_curl),
                        Modifier.weight(1f).heightIn(min = 48.dp).testTag("curl-converter-direction-$direction")
                            .selectable(state.draft.direction == direction, enabled = editable, role = Role.RadioButton) { onDirection(direction) }.padding(8.dp))
                }
            }
            if (state.loading || state.converting || state.inserting) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("curl-converter-progress"))
            if (state.loadFailed) Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.curl_converter_failed), Modifier.weight(1f).padding(12.dp))
                TextButton(onRetry, Modifier.testTag("curl-converter-retry")) { Text(stringResource(R.string.retry)) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val paneHeight = (maxHeight / 2).coerceAtLeast(140.dp)
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(editor,
                { editor = it; onInput(it.text, it.selection.start, it.selection.end) }, Modifier.fillMaxWidth().height(paneHeight).padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("curl-converter-input"), enabled = editable,
                label = { Text(stringResource(if (curlFirst) R.string.curl_input_hint else R.string.analyze_url_input_hint)) },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
            // A read-only text field retains native text selection/copy accessibility.
            OutlinedTextField(state.draft.output, {}, Modifier.fillMaxWidth().height(paneHeight).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
                .testTag("curl-converter-output"), readOnly = true,
                label = { Text(stringResource(if (curlFirst) R.string.analyze_url_output_hint else R.string.curl_output_hint)) },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}
