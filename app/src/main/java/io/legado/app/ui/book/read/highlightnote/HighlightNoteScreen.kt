package io.legado.app.ui.book.read.highlightnote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.components.LegadoTopAppBar

@Composable
fun HighlightNoteScreen(state: HighlightNoteUiState, onBookText: (String) -> Unit,
    onNote: (String) -> Unit, onSubmit: (HighlightNoteAction) -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = !state.busy && !state.finished
    Surface(modifier.fillMaxSize().imePadding().padding(16.dp), shape = MaterialTheme.shapes.medium) {
        Column {
            LegadoTopAppBar(stringResource(R.string.highlight_note), { if (enabled) onCancel() }, windowInsets = WindowInsets(0, 0, 0, 0))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.chapterName)
                OutlinedTextField(state.bookText, onBookText, enabled = enabled,
                    label = { Text(stringResource(R.string.content)) }, minLines = 2, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("highlight-note-book-text"))
                OutlinedTextField(state.note, onNote, enabled = enabled,
                    label = { Text(stringResource(R.string.note_content)) }, minLines = 4, maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("highlight-note-input"))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onRetry, enabled = enabled, modifier = Modifier.testTag("highlight-note-retry")) { Text(stringResource(R.string.retry)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ onSubmit(HighlightNoteAction.Delete) }, enabled = enabled,
                    modifier = Modifier.testTag("highlight-note-delete")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                Row {
                    TextButton(onCancel, enabled = enabled, modifier = Modifier.testTag("highlight-note-cancel")) { Text(stringResource(R.string.cancel)) }
                    TextButton({ onSubmit(HighlightNoteAction.Save) }, enabled = enabled,
                        modifier = Modifier.testTag("highlight-note-save")) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}
