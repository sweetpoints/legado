package io.legado.app.ui.widget.dialog.urloption

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
fun UrlOptionScreen(draft: UrlOptionDraft, charsets: List<String>, finished: Boolean,
    onInput: (UrlOptionField, String) -> Unit, onWebView: (Boolean) -> Unit,
    onConfirm: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val confirm = { focus.clearFocus(); keyboard?.hide(); onConfirm() }
    val close = { focus.clearFocus(); keyboard?.hide(); onClose() }
    val closeLabel = stringResource(R.string.close)
    BoxWithConstraints(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().testTag("url-option-backdrop").clickable(onClickLabel = closeLabel, onClick = close))
        Surface(onClick = {}, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = (maxHeight - 32.dp).coerceAtLeast(1.dp)).testTag("url-option-card")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.url_option), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(confirm, enabled = !finished, modifier = Modifier.testTag("url-option-confirm")) { Text(stringResource(R.string.ok)) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().testTag("url-option-webview").clickable(enabled = !finished) { onWebView(!draft.webView) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(draft.webView, null, enabled = !finished)
                        Text("useWebView")
                    }
                    UrlOptionField.entries.forEach { field ->
                        val suggestions = when (field) {
                            UrlOptionField.Method -> listOf("POST", "GET")
                            UrlOptionField.Charset -> charsets
                            else -> emptyList()
                        }
                        UrlOptionInput(field, draft[field], suggestions, !finished) { onInput(field, it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlOptionInput(field: UrlOptionField, value: String, suggestions: List<String>, enabled: Boolean, onValue: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled && suggestions.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(value, onValue, enabled = enabled, label = { Text(field.label) },
            singleLine = !field.multiline, minLines = if (field.multiline) 2 else 1, maxLines = if (field.multiline) 6 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = if (field == UrlOptionField.Retry) KeyboardType.Number else KeyboardType.Text),
            trailingIcon = if (suggestions.isEmpty()) null else ({ ExposedDropdownMenuDefaults.TrailingIcon(expanded) }),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("url-option-${field.label}")
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled && suggestions.isNotEmpty()))
        ExposedDropdownMenu(expanded = expanded && enabled && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(text = { Text(suggestion) }, onClick = { onValue(suggestion); expanded = false }, modifier = Modifier.testTag("url-option-suggestion-$suggestion"))
            }
        }
    }
}
