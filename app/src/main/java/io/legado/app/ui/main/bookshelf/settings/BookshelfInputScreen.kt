package io.legado.app.ui.main.bookshelf.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.legado.app.R

@Composable
internal fun BookshelfInputScreen(
    state: BookshelfInputState,
    kind: Int,
    summary: String,
    onEdit: (String, Int, Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onSelectFile: () -> Unit,
) {
    var value by remember {
        mutableStateOf(
            TextFieldValue(state.text, TextRange(state.selectionStart, state.selectionEnd))
        )
    }
    LaunchedEffect(state) {
        if (
            value.text != state.text ||
                value.selection.start != state.selectionStart ||
                value.selection.end != state.selectionEnd
        )
            value = TextFieldValue(state.text, TextRange(state.selectionStart, state.selectionEnd))
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    when (kind) {
                        1 -> R.string.import_bookshelf
                        2 -> R.string.export_success
                        else -> R.string.add_book_url
                    }
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            if (summary.isNotBlank()) Text(summary)
            OutlinedTextField(
                value,
                { next ->
                    value = next
                    onEdit(next.text, next.selection.start, next.selection.end)
                },
                label = {
                    Text(
                        if (kind == 2) stringResource(R.string.path)
                        else if (kind == 1) "url/json" else "url"
                    )
                },
                modifier =
                    Modifier.fillMaxWidth().heightIn(max = 240.dp).testTag("shelf-input-text"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (kind == 1)
                    TextButton(
                        onClick = onSelectFile,
                        modifier = Modifier.testTag("shelf-input-file"),
                    ) {
                        Text(stringResource(R.string.select_file))
                    }
                if (kind != 2)
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("shelf-input-cancel"),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.testTag("shelf-input-confirm"),
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
    }
}
