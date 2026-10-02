package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.MarkdownImageRepository
import io.legado.app.ui.components.markdown.*
import java.text.DateFormat
import java.util.Date

@Composable internal fun BookMemoScreen(state: BookMemoState, document: List<MarkdownBlock>, landscape: Boolean,
    imageRepository: MarkdownImageRepository, onLink: (String) -> Unit, edit: () -> Unit, save: () -> Unit,
    text: (String, Int, Int) -> Unit, cancelEdit: () -> Unit, clear: () -> Unit, close: () -> Unit,
    confirm: () -> Unit, cancelConfirmation: () -> Unit, retry: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState(); val focus = remember { FocusRequester() }; val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.editing) {
        if (state.editing) { withFrameNanos {}; focus.requestFocus(); keyboard?.show() } else keyboard?.hide()
    }
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_memo), Modifier.weight(1f).padding(vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(close, enabled = !state.saving, modifier = Modifier.testTag("memo-close")) { Text(stringResource(R.string.close)) }
                if (landscape) MemoActions(state, edit, save, cancelEdit, clear)
            }
            state.memo?.let { Text(stringResource(R.string.book_memo_updated, DateFormat.getDateTimeInstance().format(Date(it.updatedAt))),
                Modifier.testTag("memo-updated"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!state.loaded || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("memo-working"))
            state.error?.let { error -> Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.book_memo_save_failed, error), Modifier.weight(1f).testTag("memo-error"), color = MaterialTheme.colorScheme.error)
                if (!state.loaded) TextButton(retry, Modifier.testTag("memo-retry")) { Text(stringResource(R.string.retry)) }
            } }
            if (state.editing) {
                var composing by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.selectionStart, state.selectionEnd))) }
                val selected = TextRange(state.selectionStart, state.selectionEnd)
                val value = if (composing.text == state.draft && composing.selection == selected) composing else TextFieldValue(state.draft, selected)
                OutlinedTextField(value, { composing = it; text(it.text, it.selection.start, it.selection.end) },
                    Modifier.weight(1f).fillMaxWidth().focusRequester(focus).testTag("memo-editor"), enabled = !state.saving,
                    placeholder = { Text(stringResource(R.string.book_memo_hint)) })
            } else Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("memo-scroll").padding(vertical = 8.dp)) {
                if (state.loaded && state.memo?.content.isNullOrEmpty()) Text(stringResource(R.string.book_memo_empty), Modifier.testTag("memo-empty"))
                else ComposeMarkdown(document, imageRepository, onLink, Modifier.testTag("memo-content"))
            }
            if (!landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MemoActions(state, edit, save, cancelEdit, clear) }
        }
    }
    state.confirmation?.let { value -> AlertDialog(onDismissRequest = cancelConfirmation,
        text = { Text(stringResource(if (value == BookMemoConfirmation.Discard) R.string.book_memo_discard else R.string.book_memo_clear_confirm)) },
        confirmButton = { TextButton(confirm, Modifier.testTag("memo-confirm"), enabled = !state.saving) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(cancelConfirmation, Modifier.testTag("memo-confirm-cancel"), enabled = !state.saving) { Text(stringResource(R.string.cancel)) } }) }
}
@Composable private fun MemoActions(state: BookMemoState, edit: () -> Unit, save: () -> Unit, cancelEdit: () -> Unit, clear: () -> Unit) {
    TextButton(if (state.editing) save else edit, enabled = state.loaded && !state.saving, modifier = Modifier.testTag("memo-edit-save")) {
        Text(stringResource(if (state.editing) R.string.book_memo_save else R.string.edit))
    }
    TextButton(if (state.editing) cancelEdit else clear, enabled = state.loaded && !state.saving && (state.editing || !state.memo?.content.isNullOrEmpty()),
        modifier = Modifier.testTag("memo-clear-cancel")) { Text(stringResource(if (state.editing) R.string.cancel else R.string.clear)) }
}
