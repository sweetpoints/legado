package io.legado.app.ui.book.info.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.repository.BookDetailBook
import io.legado.app.data.repository.BookDetailPreferences
import io.legado.app.data.repository.BookDetailPrompt
import io.legado.app.data.repository.BookDetailPromptKind
import io.legado.app.data.repository.BookDetailWebFile

class BookDetailPromptActions(
    val dismiss: (BookDetailPrompt) -> Unit,
    val confirm: (BookDetailPrompt, Int?) -> Unit,
    val deleteOriginal: (Boolean) -> Unit,
    val deleteRemote: (BookDetailPrompt, Boolean) -> Unit,
    val uploadImported: (Boolean) -> Unit,
)

@Composable
fun BookDetailPromptScreen(
    prompt: BookDetailPrompt?,
    book: BookDetailBook?,
    files: List<BookDetailWebFile>,
    preferences: BookDetailPreferences,
    enabled: Boolean,
    actions: BookDetailPromptActions,
) {
    if (prompt == null) return
    val title =
        when (prompt.kind) {
            BookDetailPromptKind.WebFiles -> R.string.download_and_import_file
            BookDetailPromptKind.ArchiveEntries -> R.string.import_select_book
            else -> R.string.draw
        }
    val selector =
        prompt.kind == BookDetailPromptKind.WebFiles ||
            prompt.kind == BookDetailPromptKind.ArchiveEntries
    AlertDialog(
        onDismissRequest = { if (enabled) actions.dismiss(prompt) },
        title = { Text(stringResource(title)) },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .then(
                        if (selector) Modifier else Modifier.verticalScroll(rememberScrollState())
                    )
                    .testTag("book-detail-prompt")
            ) {
                when (prompt.kind) {
                    BookDetailPromptKind.Delete -> {
                        Text(stringResource(R.string.sure_del))
                        if (book?.isLocal == true) {
                            BookDetailPromptCheck(
                                stringResource(R.string.delete_book_file),
                                preferences.deleteOriginal,
                                enabled,
                                actions.deleteOriginal,
                            )
                            if (preferences.remoteConfigured)
                                BookDetailPromptCheck(
                                    stringResource(R.string.delete_webdav_book_file),
                                    prompt.deleteRemote,
                                    enabled,
                                ) {
                                    actions.deleteRemote(prompt, it)
                                }
                        }
                    }
                    BookDetailPromptKind.WebFiles -> {
                        BookDetailPromptCheck(
                            stringResource(R.string.upload_imported_book_to_webdav),
                            preferences.uploadImported,
                            enabled,
                            actions.uploadImported,
                        )
                        LazyColumn(Modifier.weight(1f, fill = false)) {
                            itemsIndexed(
                                files,
                                key = { index, file -> "$index:${file.url.hashCode()}" },
                            ) { index, file ->
                                Text(
                                    file.name,
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled) { actions.confirm(prompt, index) }
                                        .padding(vertical = 12.dp)
                                        .testTag("book-detail-file-$index"),
                                )
                            }
                        }
                    }
                    BookDetailPromptKind.ArchiveEntries ->
                        LazyColumn {
                            itemsIndexed(
                                prompt.values,
                                key = { index, value -> "$index:${value.hashCode()}" },
                            ) { index, value ->
                                Text(
                                    value,
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled) { actions.confirm(prompt, index) }
                                        .padding(vertical = 12.dp)
                                        .testTag("book-detail-archive-$index"),
                                )
                            }
                        }
                    BookDetailPromptKind.UnsupportedFile ->
                        Text(
                            stringResource(
                                R.string.file_not_supported,
                                prompt.values.firstOrNull().orEmpty(),
                            )
                        )
                    BookDetailPromptKind.OverwriteUpload ->
                        Text(stringResource(R.string.webdav_book_exists_confirm))
                    BookDetailPromptKind.Upload -> Text(stringResource(R.string.upload_to_remote))
                    BookDetailPromptKind.ExternalLink -> Text(prompt.value.orEmpty())
                }
            }
        },
        confirmButton = {
            if (!selector)
                TextButton(
                    { actions.confirm(prompt, null) },
                    enabled = enabled,
                    modifier = Modifier.testTag("book-detail-confirm"),
                ) {
                    Text(
                        stringResource(
                            if (prompt.kind == BookDetailPromptKind.UnsupportedFile)
                                R.string.open_fun
                            else R.string.yes
                        )
                    )
                }
        },
        dismissButton = {
            TextButton({ actions.dismiss(prompt) }, enabled = enabled) {
                Text(stringResource(R.string.no))
            }
        },
    )
}

@Composable
private fun BookDetailPromptCheck(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = change,
            )
    ) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.padding(top = 12.dp, bottom = 12.dp))
    }
}
